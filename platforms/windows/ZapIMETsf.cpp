// SPDX-License-Identifier: GPL-3.0-only
// 参考固定 MSIME TSF 的 COM 生命周期、同步编辑锁和失败回滚边界；保留原引擎。
#define NOMINMAX
#include <windows.h>
#include <msctf.h>
#include <ctffunc.h>
#include <shlobj.h>
#include <wrl/client.h>
#include <atomic>
#include <filesystem>
#include <new>
#include <string>
#include <nlohmann/json.hpp>
#include "Identity.h"
#include "../common/zapime.h"
using Microsoft::WRL::ComPtr;
using Json = nlohmann::json;
namespace fs = std::filesystem;

static HMODULE module;
static std::atomic<long> objects{0}, locks{0};

static std::wstring modulePath() {
    wchar_t path[32768]{};
    DWORD n = GetModuleFileNameW(module,path,32768);
    return n && n < 32768 ? std::wstring(path,n) : L"";
}
static std::string utf8(const std::wstring &value) {
    int size=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value.data(),static_cast<int>(value.size()),nullptr,0,nullptr,nullptr);
    std::string result(size,'\0');
    if(size) WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value.data(),static_cast<int>(value.size()),result.data(),size,nullptr,nullptr);
    return result;
}
static std::wstring wide(const std::string &value) {
    int size=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,value.data(),static_cast<int>(value.size()),nullptr,0);
    std::wstring result(size,L'\0');
    if(size) MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,value.data(),static_cast<int>(value.size()),result.data(),size);
    return result;
}
static Json request(uint64_t handle,const Json &value) {
    auto text=value.dump();
    auto buffer=zapime_request(handle,reinterpret_cast<const uint8_t *>(text.data()),text.size());
    // 即使 JSON 分配/解析异常，也必须归还 Rust 输出。
    struct Guard { ZapBuffer buffer; ~Guard(){zapime_buffer_free(buffer);} } guard{buffer};
    return Json::parse(buffer.data,buffer.data+buffer.len);
}

class Service;
class Edit final : public ITfEditSession {
    long refs=1;
    Service *owner;
    ComPtr<ITfContext> context;
    Json operation;
public:
    Edit(Service *,ITfContext *,Json);
    ~Edit();
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid,void **out) override {
        if(!out)return E_POINTER; *out=nullptr;
        if(iid==IID_IUnknown || iid==IID_ITfEditSession){*out=static_cast<ITfEditSession *>(this);AddRef();return S_OK;}return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
    ULONG STDMETHODCALLTYPE Release() override{auto n=--refs;if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie) override;
};

class Service final : public ITfTextInputProcessorEx, public ITfKeyEventSink,
                      public ITfThreadMgrEventSink, public ITfCompositionSink {
    long refs=1;
    ComPtr<ITfThreadMgr> manager;
    ComPtr<ITfKeystrokeMgr> keys;
    ComPtr<ITfSource> source;
    ComPtr<ITfContext> activeContext;
    ComPtr<ITfComposition> composition;
    TfClientId client=TF_CLIENTID_NULL;
    DWORD sourceCookie=TF_INVALID_COOKIE;
    uint64_t handle=0;
    bool english=false, ending=false;
    HWND candidate=nullptr;
    Json view=Json::object();
    bool composing() const {return view.contains("code") && !view["code"].get<std::string>().empty();}
    std::string typed(WPARAM key) const {
        BYTE state[256]{};wchar_t chars[4]{};
        if(!GetKeyboardState(state))return {};
        // 标志 4 保持死键状态不变，避免测试按键时改变系统键盘状态。
        int n=ToUnicodeEx(static_cast<UINT>(key),MapVirtualKeyW(static_cast<UINT>(key),MAPVK_VK_TO_VSC),state,chars,4,4,GetKeyboardLayout(0));
        return n==1&&chars[0]>=32&&chars[0]<127?std::string(1,static_cast<char>(chars[0])):std::string();
    }
    void reset() {
        if(handle)view=request(handle,{{"op","reset"}});
        if(candidate)ShowWindow(candidate,SW_HIDE);
    }
    void cancelContext() {
        if(activeContext && composition && client!=TF_CLIENTID_NULL){
            auto edit=new(std::nothrow) Edit(this,activeContext.Get(),{{"op","reset"}});
            if(edit){HRESULT result=E_FAIL;activeContext->RequestEditSession(client,edit,TF_ES_SYNC|TF_ES_READWRITE,&result);edit->Release();}
        }
        reset();composition.Reset();activeContext.Reset();
    }
    bool allowed(ITfContext *context,WPARAM key) {
        if(!handle || !context || GetKeyState(VK_CONTROL)<0 || GetKeyState(VK_MENU)<0)return false;
        ComPtr<ITfCompartmentMgr> compartments;
        if(FAILED(context->QueryInterface(IID_PPV_ARGS(&compartments))))return false;
        for(const GUID *id : {&GUID_COMPARTMENT_KEYBOARD_DISABLED,&GUID_COMPARTMENT_EMPTYCONTEXT}){
            ComPtr<ITfCompartment> compartment;
            VARIANT value;VariantInit(&value);
            if(SUCCEEDED(compartments->GetCompartment(*id,&compartment)) && SUCCEEDED(compartment->GetValue(&value))){
                bool disabled=value.vt==VT_I4 && value.lVal!=0;
                VariantClear(&value);if(disabled)return false;
            }
        }
        if(key==VK_SPACE && GetKeyState(VK_SHIFT)<0)return true;
        if(english)return false;
        // 旧文档的组字不能让新文档首个空格、数字或标点被误吞。
        return (key>='A' && key<='Z') || (activeContext.Get()==context && composing() && (key==VK_SPACE || key==VK_RETURN || key==VK_BACK || key==VK_ESCAPE || !typed(key).empty()));
    }
    void showCandidates(TfEditCookie cookie,ITfContext *context,ITfRange *range) {
        if(!composing() || !view.contains("candidates") || view["candidates"].empty()){if(candidate)ShowWindow(candidate,SW_HIDE);return;}
        std::wstring label;
        for(size_t i=0;i<view["candidates"].size()&&i<9;i++)label+=std::to_wstring(i+1)+L" "+wide(view["candidates"][i].get<std::string>())+L"  ";
        if(!candidate)candidate=CreateWindowExW(WS_EX_NOACTIVATE|WS_EX_TOPMOST|WS_EX_TOOLWINDOW,L"STATIC",L"",WS_POPUP|WS_BORDER|SS_LEFT,0,0,650,36,nullptr,nullptr,module,nullptr);
        if(!candidate)return;
        SetWindowTextW(candidate,label.c_str());
        ComPtr<ITfContextView> contextView;RECT rect{};BOOL clipped=FALSE;
        if(SUCCEEDED(context->GetActiveView(&contextView)) && SUCCEEDED(contextView->GetTextExt(cookie,range,&rect,&clipped)))
            SetWindowPos(candidate,HWND_TOPMOST,rect.left,rect.bottom,650,36,SWP_NOACTIVATE|SWP_SHOWWINDOW);
    }
public:
    Service(){++objects;}
    ~Service(){Deactivate();--objects;}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid,void **out) override {
        if(!out)return E_POINTER;*out=nullptr;
        if(iid==IID_IUnknown || iid==IID_ITfTextInputProcessor || iid==IID_ITfTextInputProcessorEx)*out=static_cast<ITfTextInputProcessorEx *>(this);
        else if(iid==IID_ITfKeyEventSink)*out=static_cast<ITfKeyEventSink *>(this);
        else if(iid==IID_ITfThreadMgrEventSink)*out=static_cast<ITfThreadMgrEventSink *>(this);
        else if(iid==IID_ITfCompositionSink)*out=static_cast<ITfCompositionSink *>(this);
        else return E_NOINTERFACE;
        AddRef();return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
    ULONG STDMETHODCALLTYPE Release() override{auto n=--refs;if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE Activate(ITfThreadMgr *m,TfClientId id) override{return ActivateEx(m,id,0);}
    HRESULT STDMETHODCALLTYPE ActivateEx(ITfThreadMgr *m,TfClientId id,DWORD) override {
        if(!m)return E_INVALIDARG;if(manager)return E_UNEXPECTED;
        try {
            PWSTR appData=nullptr;
            if(FAILED(SHGetKnownFolderPath(FOLDERID_LocalAppData,0,nullptr,&appData)))return E_FAIL;
            fs::path root=fs::path(appData)/L"ZapIME";CoTaskMemFree(appData);
            Json config={{"resources",utf8((fs::path(modulePath()).parent_path()/L"EngineResources").wstring())},
                         {"data",utf8((root/L"data").wstring())},{"cache",utf8((root/L"cache").wstring())},{"private",true}};
            auto text=config.dump();handle=zapime_open(reinterpret_cast<const uint8_t *>(text.data()),text.size());
            if(!handle)return E_FAIL;
            manager=m;client=id;
            HRESULT hr=m->QueryInterface(IID_PPV_ARGS(&keys));
            if(SUCCEEDED(hr))hr=keys->AdviseKeyEventSink(client,this,TRUE);
            if(SUCCEEDED(hr))hr=m->QueryInterface(IID_PPV_ARGS(&source));
            if(SUCCEEDED(hr))hr=source->AdviseSink(IID_ITfThreadMgrEventSink,static_cast<ITfThreadMgrEventSink *>(this),&sourceCookie);
            if(FAILED(hr)){Deactivate();return hr;}
            view=request(handle,{{"op","view"}});return S_OK;
        }catch(...){Deactivate();return E_FAIL;}
    }
    HRESULT STDMETHODCALLTYPE Deactivate() override {
        if(source && sourceCookie!=TF_INVALID_COOKIE)source->UnadviseSink(sourceCookie);
        sourceCookie=TF_INVALID_COOKIE;source.Reset();
        if(keys && client!=TF_CLIENTID_NULL)keys->UnadviseKeyEventSink(client);
        keys.Reset();manager.Reset();activeContext.Reset();composition.Reset();client=TF_CLIENTID_NULL;
        if(handle){zapime_close(handle);handle=0;}
        view.clear();if(candidate){DestroyWindow(candidate);candidate=nullptr;}return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnSetFocus(BOOL foreground) override {try{if(!foreground)cancelContext();return S_OK;}catch(...){return E_FAIL;}}
    HRESULT STDMETHODCALLTYPE OnTestKeyDown(ITfContext *context,WPARAM key,LPARAM,BOOL *eaten) override {
        if(!eaten)return E_POINTER;try{*eaten=allowed(context,key);return S_OK;}catch(...){*eaten=FALSE;return E_FAIL;}
    }
    HRESULT STDMETHODCALLTYPE OnKeyDown(ITfContext *context,WPARAM key,LPARAM,BOOL *eaten) override {
        if(!eaten)return E_POINTER;*eaten=FALSE;
        try {
            if(!allowed(context,key))return S_OK;
            if(activeContext.Get()!=context){cancelContext();activeContext=context;}
            Json op;
            if(key==VK_SPACE && GetKeyState(VK_SHIFT)<0){op={{"op","command"},{"name","CommitRaw"}};}
            else if(key==VK_ESCAPE)op={{"op","reset"}};
            else if(key==VK_BACK)op={{"op","command"},{"name","Backspace"}};
            else if(key==VK_RETURN)op={{"op","command"},{"name","CommitRaw"}};
            else if(key==VK_SPACE)op={{"op","command"},{"name","CommitCandidate"}};
            else if(key>='1'&&key<='9'){
                if(static_cast<size_t>(key-'1')>=view["candidates"].size()){*eaten=TRUE;return S_OK;}
                op={{"op","select"},{"index",key-'1'},{"generation",view["generation"]}};
            }else {auto text=typed(key);if(text.empty())return S_OK;op={{"op","key"},{"text",text}};}
            auto edit=new(std::nothrow) Edit(this,context,std::move(op));if(!edit)return E_OUTOFMEMORY;
            HRESULT result=E_FAIL;
            auto hr=context->RequestEditSession(client,edit,TF_ES_SYNC|TF_ES_READWRITE,&result);edit->Release();
            if(SUCCEEDED(hr)&&SUCCEEDED(result)){*eaten=TRUE;if(key==VK_SPACE&&GetKeyState(VK_SHIFT)<0)english=!english;}
            else reset();
            return S_OK;
        }catch(...){return E_FAIL;}
    }
    HRESULT STDMETHODCALLTYPE OnTestKeyUp(ITfContext *,WPARAM,LPARAM,BOOL *eaten) override {if(!eaten)return E_POINTER;*eaten=FALSE;return S_OK;}
    HRESULT STDMETHODCALLTYPE OnKeyUp(ITfContext *,WPARAM,LPARAM,BOOL *eaten) override {if(!eaten)return E_POINTER;*eaten=FALSE;return S_OK;}
    HRESULT STDMETHODCALLTYPE OnPreservedKey(ITfContext *,REFGUID,BOOL *eaten) override {if(!eaten)return E_POINTER;*eaten=FALSE;return S_OK;}
    HRESULT STDMETHODCALLTYPE OnInitDocumentMgr(ITfDocumentMgr *) override{return S_OK;}
    HRESULT STDMETHODCALLTYPE OnUninitDocumentMgr(ITfDocumentMgr *) override{return S_OK;}
    HRESULT STDMETHODCALLTYPE OnSetFocus(ITfDocumentMgr *,ITfDocumentMgr *) override {try{cancelContext();return S_OK;}catch(...){return E_FAIL;}}
    HRESULT STDMETHODCALLTYPE OnPushContext(ITfContext *) override {return S_OK;}
    HRESULT STDMETHODCALLTYPE OnPopContext(ITfContext *context) override {try{if(activeContext.Get()==context)cancelContext();return S_OK;}catch(...){return E_FAIL;}}
    HRESULT STDMETHODCALLTYPE OnCompositionTerminated(TfEditCookie,ITfComposition *) override {try{if(!ending){composition.Reset();reset();}return S_OK;}catch(...){return E_FAIL;}}

    HRESULT apply(TfEditCookie cookie,ITfContext *context,const Json &op) {
        if(activeContext.Get()!=context)return E_ABORT;
        auto next=request(handle,op);if(next.contains("error"))return E_FAIL;
        const bool committed=next.contains("commit")&&next["commit"].is_string();
        auto preedit=next.value("preedit",std::string());
        // 空组字的中英切换不能对编辑器选区执行 SetText("")，否则会误删选中正文。
        if(!composition&&!committed&&preedit.empty()){view=std::move(next);return S_OK;}
        ComPtr<ITfRange> range;
        HRESULT hr=S_OK;
        if(composition)hr=composition->GetRange(&range);
        else {
            TF_SELECTION selection{};ULONG fetched=0;
            hr=context->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&selection,&fetched);
            if(SUCCEEDED(hr)&&fetched==1)range.Attach(selection.range);else return E_FAIL;
        }
        if(FAILED(hr))return hr;
        auto text=wide(committed?next["commit"].get<std::string>():preedit);
        hr=range->SetText(cookie,0,text.data(),static_cast<LONG>(text.size()));if(FAILED(hr))return hr;
        if((committed||preedit.empty())&&composition){ending=true;hr=composition->EndComposition(cookie);ending=false;composition.Reset();if(FAILED(hr))return hr;}
        else if(!preedit.empty()&&!composition){ComPtr<ITfContextComposition> composer;hr=context->QueryInterface(IID_PPV_ARGS(&composer));if(SUCCEEDED(hr))hr=composer->StartComposition(cookie,range.Get(),this,&composition);if(FAILED(hr))return hr;}
        ComPtr<ITfRange> caret;hr=range->Clone(&caret);if(FAILED(hr))return hr;
        hr=caret->Collapse(cookie,TF_ANCHOR_END);if(FAILED(hr))return hr;
        TF_SELECTION selection{caret.Get(),{TF_AE_NONE,FALSE}};hr=context->SetSelection(cookie,1,&selection);if(FAILED(hr))return hr;
        view=std::move(next);showCandidates(cookie,context,range.Get());return S_OK;
    }
};
Edit::Edit(Service *service,ITfContext *ctx,Json op):owner(service),context(ctx),operation(std::move(op)){owner->AddRef();++objects;}
Edit::~Edit(){owner->Release();--objects;}
HRESULT STDMETHODCALLTYPE Edit::DoEditSession(TfEditCookie cookie){try{return owner->apply(cookie,context.Get(),operation);}catch(...){return E_FAIL;}}

class Factory final:public IClassFactory {
    std::atomic<long> refs{1};
public:
    Factory(){++objects;}~Factory(){--objects;}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid,void **out) override {if(!out)return E_POINTER;*out=nullptr;if(iid!=IID_IUnknown&&iid!=IID_IClassFactory)return E_NOINTERFACE;*out=static_cast<IClassFactory *>(this);AddRef();return S_OK;}
    ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
    ULONG STDMETHODCALLTYPE Release() override{auto n=--refs;if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown *outer,REFIID iid,void **out) override {if(!out)return E_POINTER;*out=nullptr;if(outer)return CLASS_E_NOAGGREGATION;try{auto instance=new(std::nothrow) Service();if(!instance)return E_OUTOFMEMORY;auto hr=instance->QueryInterface(iid,out);instance->Release();return hr;}catch(...){return E_OUTOFMEMORY;}}
    HRESULT STDMETHODCALLTYPE LockServer(BOOL lock) override {if(lock)++locks;else{long n=locks.load();while(n>0&&!locks.compare_exchange_weak(n,n-1)){}if(n<=0)return E_UNEXPECTED;}return S_OK;}
};

extern "C" HRESULT __stdcall DllGetClassObject(REFCLSID clsid,REFIID iid,void **out){if(!out)return E_POINTER;*out=nullptr;if(clsid!=ZapClsid)return CLASS_E_CLASSNOTAVAILABLE;auto factory=new(std::nothrow) Factory();if(!factory)return E_OUTOFMEMORY;auto hr=factory->QueryInterface(iid,out);factory->Release();return hr;}
extern "C" HRESULT __stdcall DllCanUnloadNow(){return objects.load()==0&&locks.load()==0?S_OK:S_FALSE;}

static HRESULT registration(bool install) {
    // 只改本产品的当前用户 COM 项；不自动激活输入源或重置用户的其他键盘。
    const std::wstring key=std::wstring(L"Software\\Classes\\CLSID\\")+ZapClsidText;
    ComPtr<ITfInputProcessorProfileMgr> profiles;
    ComPtr<ITfCategoryMgr> categories;
    HRESULT hr=CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&profiles));if(FAILED(hr))return hr;
    hr=CoCreateInstance(CLSID_TF_CategoryMgr,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&categories));if(FAILED(hr))return hr;
    auto remove=[&](){
        HRESULT a=profiles->UnregisterProfile(ZapClsid,ZapLanguage,ZapProfile,0);
        HRESULT b=categories->UnregisterCategory(ZapClsid,GUID_TFCAT_TIP_KEYBOARD,ZapClsid);
        LSTATUS c=RegDeleteTreeW(HKEY_CURRENT_USER,key.c_str());
        return FAILED(a)?a:FAILED(b)?b:(c==ERROR_SUCCESS||c==ERROR_FILE_NOT_FOUND)?S_OK:HRESULT_FROM_WIN32(c);
    };
    if(!install)return remove();
    HKEY opened=nullptr;
    auto path=modulePath();if(path.empty())return E_FAIL;
    auto error=RegCreateKeyExW(HKEY_CURRENT_USER,(key+L"\\InprocServer32").c_str(),0,nullptr,0,KEY_WRITE,nullptr,&opened,nullptr);
    if(error!=ERROR_SUCCESS)return HRESULT_FROM_WIN32(error);
    error=RegSetValueExW(opened,nullptr,0,REG_SZ,reinterpret_cast<const BYTE *>(path.c_str()),static_cast<DWORD>((path.size()+1)*sizeof(wchar_t)));
    if(error==ERROR_SUCCESS)error=RegSetValueExW(opened,L"ThreadingModel",0,REG_SZ,reinterpret_cast<const BYTE *>(L"Apartment"),sizeof(L"Apartment"));
    RegCloseKey(opened);if(error!=ERROR_SUCCESS){remove();return HRESULT_FROM_WIN32(error);}
    const wchar_t name[]=L"闪电输入法 ZapIME";
    hr=profiles->RegisterProfile(ZapClsid,ZapLanguage,ZapProfile,name,static_cast<ULONG>(wcslen(name)),path.c_str(),static_cast<ULONG>(path.size()),0,nullptr,0,TRUE,0);
    if(SUCCEEDED(hr))hr=categories->RegisterCategory(ZapClsid,GUID_TFCAT_TIP_KEYBOARD,ZapClsid);
    if(FAILED(hr))remove();return hr;
}
extern "C" HRESULT __stdcall DllRegisterServer(){try{return registration(true);}catch(...){return E_FAIL;}}
extern "C" HRESULT __stdcall DllUnregisterServer(){try{return registration(false);}catch(...){return E_FAIL;}}
BOOL WINAPI DllMain(HINSTANCE instance,DWORD reason,LPVOID){if(reason==DLL_PROCESS_ATTACH)module=instance;return TRUE;}
