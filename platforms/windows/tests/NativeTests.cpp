// SPDX-License-Identifier: GPL-3.0-only
#include <windows.h>
#include <msctf.h>
#include <ctffunc.h>
#include <filesystem>
#include <iostream>
#include <stdexcept>
#include <nlohmann/json.hpp>
#include "../Identity.h"
#include "../../common/zapime.h"
using Json=nlohmann::json;
static void require(bool condition,const char *message){if(!condition)throw std::runtime_error(message);}
static Json request(uint64_t handle,const Json &op){
    auto text=op.dump();auto buffer=zapime_request(handle,reinterpret_cast<const uint8_t *>(text.data()),text.size());
    struct Guard{ZapBuffer b;~Guard(){zapime_buffer_free(b);}}guard{buffer};
    return Json::parse(buffer.data,buffer.data+buffer.len);
}
int main(){
    try{
        require(SUCCEEDED(CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED)),"COM init");
        auto dll=LoadLibraryW(L"ZapIMETsf.dll");require(dll!=nullptr,"native TSF DLL load");
        auto get=reinterpret_cast<HRESULT(__stdcall *)(REFCLSID,REFIID,void **)>(GetProcAddress(dll,"DllGetClassObject"));
        auto unload=reinterpret_cast<HRESULT(__stdcall *)()>(GetProcAddress(dll,"DllCanUnloadNow"));
        require(get&&unload,"COM exports");require(unload()==S_OK,"initial unloadability");
        require(get(ZapClsid,IID_IClassFactory,nullptr)==E_POINTER,"null output rejection");
        void *result=nullptr;
        require(get(GUID_NULL,IID_IClassFactory,&result)==CLASS_E_CLASSNOTAVAILABLE&&result==nullptr,"foreign CLSID isolation");
        IClassFactory *factory=nullptr;require(SUCCEEDED(get(ZapClsid,IID_IClassFactory,reinterpret_cast<void **>(&factory))),"class factory");
        require(unload()==S_FALSE,"factory keeps DLL alive");
        require(factory->CreateInstance(factory,IID_IUnknown,&result)==CLASS_E_NOAGGREGATION,"reject COM aggregation");
        require(factory->LockServer(TRUE)==S_OK&&factory->LockServer(FALSE)==S_OK,"balanced server locks");
        require(factory->LockServer(FALSE)==E_UNEXPECTED,"reject lock underflow");
        ITfTextInputProcessorEx *processor=nullptr;
        require(SUCCEEDED(factory->CreateInstance(nullptr,IID_ITfTextInputProcessorEx,reinterpret_cast<void **>(&processor))),"native TSF processor");
        ITfKeyEventSink *sink=nullptr;require(SUCCEEDED(processor->QueryInterface(IID_ITfKeyEventSink,reinterpret_cast<void **>(&sink))),"native key event sink");
        BOOL eaten=TRUE;require(SUCCEEDED(sink->OnTestKeyDown(nullptr,'N',0,&eaten))&&!eaten,"inactive TIP must not consume input");
        require(processor->Activate(nullptr,1)==E_INVALIDARG,"null thread manager rejection");
        require(processor->Deactivate()==S_OK,"inactive deactivation");sink->Release();processor->Release();factory->Release();
        require(unload()==S_OK,"COM references release");FreeLibrary(dll);CoUninitialize();
        auto base=std::filesystem::current_path();auto root=base/L"native-test-profile";
        Json config={{"resources",(base/L"EngineResources").u8string()},{"data",(root/L"data").u8string()},{"cache",(root/L"cache").u8string()},{"private",true}};
        auto bytes=config.dump();auto handle=zapime_open(reinterpret_cast<const uint8_t *>(bytes.data()),bytes.size());require(handle!=0,"packaged offline resources");
        Json view;for(char c:std::string("nihao"))view=request(handle,{{"op","key"},{"text",std::string(1,c)}});
        require(view["candidates"][0]=="你好","native Windows pinyin candidate");
        auto selected=request(handle,{{"op","select"},{"index",0},{"generation",view["generation"]}});
        require(selected["commit"]=="你好"&&selected["preedit"]=="","native Windows UTF-8 commit");
        require(request(handle,{{"op","personal_export"}})["error"]=="private_session","private boundary");
        require(zapime_close(handle),"native session release");
        std::filesystem::remove_all(root);
        std::cout<<"PASS: TSF COM lifecycle and native engine bridge (not registered-system typing)\n";
        return 0;
    }catch(const std::exception &e){std::cerr<<"FAIL: "<<e.what()<<"\n";return 1;}
}
