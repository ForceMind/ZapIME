// SPDX-License-Identifier: GPL-3.0-only
// System-profile probe ONLY for disposable GitHub-hosted Windows runners.
#include <windows.h>
#include <msctf.h>
#include <ctffunc.h>
#include <shlobj.h>
#include <wrl/client.h>
#include <filesystem>
#include <iostream>
#include <string>
#include <vector>
#include <stdexcept>
#include "../Identity.h"
using Microsoft::WRL::ComPtr;
namespace fs=std::filesystem;
using Registration=HRESULT(__stdcall *)();
static void require(bool value,const char *phase){if(!value)throw std::runtime_error(phase);}
static void result(const char *phase,HRESULT hr){std::cout<<"profile_probe "<<phase<<" hr=0x"<<std::hex<<static_cast<unsigned long>(hr)<<std::dec<<"\n";}
static std::string guidText(REFGUID guid){wchar_t text[40]{};int length=StringFromGUID2(guid,text,40);return length>0?std::string(text,text+length-1):"unavailable";}
static void checked(const char *phase,HRESULT hr){result(phase,hr);require(hr==S_OK,phase);}
static std::wstring env(const wchar_t *key){wchar_t value[256]{};DWORD n=GetEnvironmentVariableW(key,value,256);return n>0&&n<256?std::wstring(value,n):L"";}
static bool digits(const std::wstring &s){return !s.empty()&&s.find_first_not_of(L"0123456789")==std::wstring::npos;}
static bool absentKey(const std::wstring &key){HKEY h=nullptr;LSTATUS e=RegOpenKeyExW(HKEY_CURRENT_USER,key.c_str(),0,KEY_READ,&h);if(h)RegCloseKey(h);return e==ERROR_FILE_NOT_FOUND||e==ERROR_PATH_NOT_FOUND;}
struct Probe {
    bool com=false,owned=false,threadActive=false,activationAttempted=false,loadedLayout=false,haveBefore=false,ownsData=false;
    HMODULE dll=nullptr;
    HKL beforeLayout=nullptr,chineseLayout=nullptr;
    Registration unregisterProfile=nullptr;
    ComPtr<ITfInputProcessorProfileMgr> profiles;
    ComPtr<ITfThreadMgr> threads;
    TF_INPUTPROCESSORPROFILE before{};
    fs::path data;
    const std::wstring key=std::wstring(L"Software\\Classes\\CLSID\\")+ZapClsidText;
    bool cleanup() noexcept {
        bool ok=true;
        if(activationAttempted&&profiles){HRESULT hr=profiles->DeactivateProfile(TF_PROFILETYPE_INPUTPROCESSOR,ZapLanguage,ZapClsid,ZapProfile,nullptr,TF_IPPMF_FORPROCESS);result("deactivate",hr);ok=SUCCEEDED(hr)&&ok;activationAttempted=false;}
        if(beforeLayout){ok=ActivateKeyboardLayout(beforeLayout,0)!=nullptr&&ok;beforeLayout=nullptr;}
        if(haveBefore&&profiles){HRESULT hr=profiles->ActivateProfile(before.dwProfileType,before.langid,before.clsid,before.guidProfile,before.hkl,TF_IPPMF_FORPROCESS);result("restore_previous",hr);ok=hr==S_OK&&ok;haveBefore=false;}
        if(threadActive&&threads){HRESULT hr=threads->Deactivate();result("thread_deactivate",hr);ok=hr==S_OK&&ok;threadActive=false;}
        threads.Reset();
        if(owned&&unregisterProfile){HRESULT hr=unregisterProfile();result("unregister",hr);ok=hr==S_OK&&ok;TF_INPUTPROCESSORPROFILE removed{};hr=profiles->GetProfile(TF_PROFILETYPE_INPUTPROCESSOR,ZapLanguage,ZapClsid,ZapProfile,nullptr,&removed);result("query_after_unregister",hr);ok=hr!=S_OK&&absentKey(key)&&ok;owned=false;}
        profiles.Reset();
        if(dll){FreeLibrary(dll);dll=nullptr;}
        if(com){CoFreeUnusedLibrariesEx(0,0);CoUninitialize();com=false;}
        if(loadedLayout&&chineseLayout){ok=UnloadKeyboardLayout(chineseLayout)!=FALSE&&ok;loadedLayout=false;}
        if(ownsData){std::error_code error;fs::remove_all(data,error);ok=!error&&ok;ownsData=false;}
        std::cout<<"profile_probe cleanup="<<(ok?"PASS":"FAIL")<<"\n";
        return ok;
    }
};
int main(){
    Probe probe;int code=1;
    try{
        // This executable is not part of local ctest/build_windows execution.
        require(env(L"GITHUB_ACTIONS")==L"true"&&env(L"RUNNER_ENVIRONMENT")==L"github-hosted"&&digits(env(L"GITHUB_RUN_ID"))&&digits(env(L"GITHUB_RUN_ATTEMPT")),"dedicated_github_hosted_runner_required");
        HRESULT comHr=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);probe.com=SUCCEEDED(comHr);checked("com_init",comHr);
        checked("profiles",CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&probe.profiles)));
        TF_INPUTPROCESSORPROFILE existing{};HRESULT existingHr=probe.profiles->GetProfile(TF_PROFILETYPE_INPUTPROCESSOR,ZapLanguage,ZapClsid,ZapProfile,nullptr,&existing);
        require(existingHr!=S_OK&&absentKey(probe.key),"refuse_existing_zapime_registration");
        PWSTR appData=nullptr;checked("local_appdata",SHGetKnownFolderPath(FOLDERID_LocalAppData,0,nullptr,&appData));probe.data=fs::path(appData)/L"ZapIME";CoTaskMemFree(appData);
        require(!fs::exists(probe.data),"refuse_existing_zapime_data");
        wchar_t module[MAX_PATH]{};DWORD length=GetModuleFileNameW(nullptr,module,MAX_PATH);require(length>0&&length<MAX_PATH,"executable_path");
        fs::path path=fs::path(module).parent_path()/L"ZapIMETsf.dll";
        probe.dll=LoadLibraryExW(path.c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);require(probe.dll!=nullptr,"load_exact_sibling_dll");
        auto install=reinterpret_cast<Registration>(GetProcAddress(probe.dll,"DllRegisterServer"));probe.unregisterProfile=reinterpret_cast<Registration>(GetProcAddress(probe.dll,"DllUnregisterServer"));require(install&&probe.unregisterProfile,"registration_exports");
        // Own cleanup before calling an installer that can partially fail.
        probe.owned=true;checked("register",install());
        TF_INPUTPROCESSORPROFILE registered{};checked("query_registered",probe.profiles->GetProfile(TF_PROFILETYPE_INPUTPROCESSOR,ZapLanguage,ZapClsid,ZapProfile,nullptr,&registered));
        std::cout<<"profile_probe queried type="<<registered.dwProfileType<<" lang=0x"<<std::hex<<registered.langid<<" flags=0x"<<registered.dwFlags<<std::dec<<" clsid="<<guidText(registered.clsid)<<" profile="<<guidText(registered.guidProfile)<<" category="<<guidText(registered.catid)<<"\n";
        require(registered.dwProfileType==TF_PROFILETYPE_INPUTPROCESSOR&&registered.langid==ZapLanguage&&registered.clsid==ZapClsid&&registered.guidProfile==ZapProfile,"registered_identity");
        require(registered.catid==GUID_TFCAT_TIP_KEYBOARD,"registered_keyboard_category");
        require((registered.dwFlags&TF_IPP_FLAG_ENABLED)!=0,"registered_enabled");
        wchar_t registeredPath[MAX_PATH]{};DWORD pathBytes=sizeof(registeredPath);
        require(RegGetValueW(HKEY_CURRENT_USER,(probe.key+L"\\InprocServer32").c_str(),nullptr,RRF_RT_REG_SZ,nullptr,registeredPath,&pathBytes)==ERROR_SUCCESS&&fs::equivalent(fs::path(registeredPath),path),"registered_com_path_is_exact_dll");
        ComPtr<ITfTextInputProcessorEx> registeredProcessor;checked("registered_com_object",CoCreateInstance(ZapClsid,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&registeredProcessor)));registeredProcessor.Reset();
        std::cout<<"profile_probe registered_identity_category_enabled=PASS\n";
        checked("thread_manager",CoCreateInstance(CLSID_TF_ThreadMgr,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&probe.threads)));TfClientId client=TF_CLIENTID_NULL;checked("thread_activate",probe.threads->Activate(&client));probe.threadActive=true;
        HRESULT beforeHr=probe.profiles->GetActiveProfile(GUID_TFCAT_TIP_KEYBOARD,&probe.before);result("query_previous",beforeHr);probe.haveBefore=beforeHr==S_OK;
        probe.beforeLayout=GetKeyboardLayout(0);require(probe.beforeLayout!=nullptr,"initial_thread_layout");
        int count=GetKeyboardLayoutList(0,nullptr);require(count>0&&count<=256,"keyboard_layout_count");std::vector<HKL> layouts(static_cast<size_t>(count));require(GetKeyboardLayoutList(count,layouts.data())==count,"keyboard_layout_snapshot");
        for(HKL layout:layouts)if(LOWORD(reinterpret_cast<ULONG_PTR>(layout))==ZapLanguage){probe.chineseLayout=layout;break;}
        if(!probe.chineseLayout){probe.chineseLayout=LoadKeyboardLayoutW(L"00000804",KLF_NOTELLSHELL);probe.loadedLayout=probe.chineseLayout!=nullptr;}
        require(probe.chineseLayout!=nullptr&&LOWORD(reinterpret_cast<ULONG_PTR>(probe.chineseLayout))==ZapLanguage,"chinese_layout_available");
        require(ActivateKeyboardLayout(probe.chineseLayout,0)!=nullptr&&LOWORD(reinterpret_cast<ULONG_PTR>(GetKeyboardLayout(0)))==ZapLanguage,"thread_language_matches");
        // No FORSESSION / default-profile update / deferred activation flag.
        probe.ownsData=true;probe.activationAttempted=true;checked("activate_for_process",probe.profiles->ActivateProfile(TF_PROFILETYPE_INPUTPROCESSOR,ZapLanguage,ZapClsid,ZapProfile,nullptr,TF_IPPMF_FORPROCESS));
        TF_INPUTPROCESSORPROFILE active{};checked("query_active",probe.profiles->GetActiveProfile(GUID_TFCAT_TIP_KEYBOARD,&active));
        require(active.dwProfileType==TF_PROFILETYPE_INPUTPROCESSOR&&active.clsid==ZapClsid&&active.guidProfile==ZapProfile&&active.langid==ZapLanguage&&(active.dwFlags&TF_IPP_FLAG_ACTIVE)!=0,"active_profile_is_zapime");
        std::cout<<"PASS process-local TSF profile registration and activation; NOT application typing\n";code=0;
    }catch(const std::exception &e){std::cerr<<"FAIL profile_probe "<<e.what()<<"\n";}
    if(!probe.cleanup())code=1;
    return code;
}
