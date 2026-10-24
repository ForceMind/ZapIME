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
#include <algorithm>
#include <stdexcept>
#include "../Identity.h"
using Microsoft::WRL::ComPtr;
namespace fs=std::filesystem;
using Registration=HRESULT(__stdcall *)();
static void require(bool value,const char *phase){if(!value)throw std::runtime_error(phase);}
static void result(const char *phase,HRESULT hr){std::cout<<"profile_probe "<<phase<<" hr=0x"<<std::hex<<static_cast<unsigned long>(hr)<<std::dec<<"\n";}
static std::string guidText(REFGUID guid){wchar_t text[40]{};int length=StringFromGUID2(guid,text,40);std::string out;for(int i=0;i<length-1;++i)out.push_back(static_cast<char>(text[i]));return length>0?out:"unavailable";}
static void checked(const char *phase,HRESULT hr){result(phase,hr);require(hr==S_OK,phase);}
static std::wstring env(const wchar_t *key){wchar_t value[256]{};DWORD n=GetEnvironmentVariableW(key,value,256);return n>0&&n<256?std::wstring(value,n):L"";}
static bool digits(const std::wstring &s){return !s.empty()&&s.find_first_not_of(L"0123456789")==std::wstring::npos;}
static bool absentKey(const std::wstring &key){HKEY h=nullptr;LSTATUS e=RegOpenKeyExW(HKEY_CURRENT_USER,key.c_str(),0,KEY_READ,&h);if(h)RegCloseKey(h);return e==ERROR_FILE_NOT_FOUND||e==ERROR_PATH_NOT_FOUND;}
static HRESULT keyboardCategory(ITfCategoryMgr *categories,bool &present){
    present=false;ComPtr<IEnumGUID> items;HRESULT hr=categories->EnumItemsInCategory(GUID_TFCAT_TIP_KEYBOARD,&items);if(hr!=S_OK||!items)return hr==S_OK?E_UNEXPECTED:hr;
    for(int page=0;page<256;++page){GUID values[16]{};ULONG count=0;hr=items->Next(16,values,&count);if(FAILED(hr))return hr;if(count>16)return E_UNEXPECTED;
        for(ULONG i=0;i<count;++i)if(values[i]==ZapClsid){present=true;return S_OK;}
        if(hr==S_FALSE)return S_OK;if(count==0)return E_UNEXPECTED;
    }
    return E_FAIL;
}
// Read only this product's fixed GUID keys; never enumerate users or dump values.
static bool absentAt(HKEY root,const std::wstring &key){HKEY h=nullptr;LSTATUS status=RegOpenKeyExW(root,key.c_str(),0,KEY_READ,&h);if(h)RegCloseKey(h);return status==ERROR_FILE_NOT_FOUND||status==ERROR_PATH_NOT_FOUND;}
static void registryPresence(const char *phase,HKEY root,const std::wstring &key){
    HKEY h=nullptr;LSTATUS status=RegOpenKeyExW(root,key.c_str(),0,KEY_READ,&h);if(h)RegCloseKey(h);
    std::cout<<"profile_probe registry "<<phase<<" status="<<status<<" present="<<(status==ERROR_SUCCESS?1:0)<<"\n";
}
static void environmentSummary(){
    HANDLE token=nullptr;TOKEN_ELEVATION elevation{};DWORD bytes=0,tokenStatus=ERROR_SUCCESS;BOOL tokenKnown=FALSE;
    if(OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&token)){
        tokenKnown=GetTokenInformation(token,TokenElevation,&elevation,sizeof(elevation),&bytes);
        if(!tokenKnown)tokenStatus=GetLastError();CloseHandle(token);
    }else tokenStatus=GetLastError();
    DWORD enableLua=2,luaBytes=sizeof(enableLua);
    LSTATUS luaStatus=RegGetValueW(HKEY_LOCAL_MACHINE,L"Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System",L"EnableLUA",RRF_RT_REG_DWORD,nullptr,&enableLua,&luaBytes);
    std::cout<<"profile_probe com_security token_known="<<(tokenKnown?1:0)<<" token_status="<<tokenStatus
             <<" elevated="<<(tokenKnown?elevation.TokenIsElevated:2)<<" enable_lua_status="<<luaStatus
             <<" enable_lua="<<(luaStatus==ERROR_SUCCESS?enableLua:2)<<"\n";

    DWORD session=0;BOOL known=ProcessIdToSessionId(GetCurrentProcessId(),&session);
    USEROBJECTFLAGS flags{};bytes=0;BOOL station=GetUserObjectInformationW(GetProcessWindowStation(),UOI_FLAGS,&flags,sizeof(flags),&bytes);
    std::cout<<"profile_probe environment session_known="<<(known?1:0)<<" session="<<session
             <<" visible_station="<<(station&&(flags.dwFlags&WSF_VISIBLE)?1:0)
             <<" remote_session="<<GetSystemMetrics(SM_REMOTESESSION)<<"\n";
}
struct OwnedFocusWindow {
    HWND focusWindow=nullptr,focusEdit=nullptr,priorForeground=nullptr;
    bool windowClassRegistered=false;
    static constexpr const wchar_t *focusClass=L"ZapIMEIsolatedProfileProbe";
    void createFocusWindow(){
        priorForeground=GetForegroundWindow();
        WNDCLASSW windowClass{};windowClass.lpfnWndProc=DefWindowProcW;windowClass.hInstance=GetModuleHandleW(nullptr);windowClass.lpszClassName=focusClass;
        require(RegisterClassW(&windowClass)!=0,"register_owned_focus_class");windowClassRegistered=true;
        focusWindow=CreateWindowExW(0,focusClass,L"ZapIME isolated profile probe",WS_OVERLAPPEDWINDOW|WS_VISIBLE,CW_USEDEFAULT,CW_USEDEFAULT,600,260,nullptr,nullptr,windowClass.hInstance,nullptr);
        require(focusWindow!=nullptr,"create_owned_focus_window");
        focusEdit=CreateWindowExW(WS_EX_CLIENTEDGE,L"EDIT",L"",WS_CHILD|WS_VISIBLE|WS_TABSTOP|ES_AUTOHSCROLL,20,30,540,100,focusWindow,nullptr,windowClass.hInstance,nullptr);
        require(focusEdit!=nullptr,"create_owned_focus_editor");
        ShowWindow(focusWindow,SW_SHOWNORMAL);SetForegroundWindow(focusWindow);SetFocus(focusEdit);
        ULONGLONG deadline=GetTickCount64()+2000;
        while(GetTickCount64()<deadline&&(GetForegroundWindow()!=focusWindow||GetFocus()!=focusEdit)){
            MSG message{};for(int count=0;count<64&&PeekMessageW(&message,nullptr,0,0,PM_REMOVE);++count){require(message.message!=WM_QUIT,"owned_focus_window_quit");TranslateMessage(&message);DispatchMessageW(&message);}
            MsgWaitForMultipleObjectsEx(0,nullptr,20,QS_ALLINPUT,MWMO_INPUTAVAILABLE);
        }
        require(GetForegroundWindow()==focusWindow&&GetFocus()==focusEdit,"owned_window_keyboard_focus_required");
        std::cout<<"profile_probe owned_window_keyboard_focus=PASS; no injected input\n";
    }
    bool closeFocusWindow() noexcept {
        bool ok=true;
        if(focusWindow){ok=DestroyWindow(focusWindow)!=FALSE&&ok;focusWindow=nullptr;focusEdit=nullptr;}
        if(windowClassRegistered){ok=UnregisterClassW(focusClass,GetModuleHandleW(nullptr))!=FALSE&&ok;windowClassRegistered=false;}
        if(priorForeground&&IsWindow(priorForeground)){
            if(GetForegroundWindow()!=priorForeground)SetForegroundWindow(priorForeground);
            ok=GetForegroundWindow()==priorForeground&&ok;
        }
        priorForeground=nullptr;return ok;
    }
    ~OwnedFocusWindow(){closeFocusWindow();}
};
// Independent-process runtime setup. It never registers profiles, loads a
// new HKL, edits the user language list, or explicitly activates ZapIME.
struct QueryRuntime {
    OwnedFocusWindow focus;
    ComPtr<ITfThreadMgr> threads;
    ComPtr<ITfInputProcessorProfiles> languages;
    HKL beforeLayout=nullptr;
    LANGID beforeLanguage=0;
    bool active=false,languageAttempted=false,finished=false;
    HRESULT begin(){
        beforeLayout=GetKeyboardLayout(0);if(!beforeLayout)return E_FAIL;
        try{focus.createFocusWindow();std::cout<<"profile_probe child_runtime_focus=PASS\n";}
        catch(const std::exception &error){std::cerr<<"profile_probe child_runtime_focus_failed="<<error.what()<<"\n";return E_FAIL;}
        HRESULT hr=CoCreateInstance(CLSID_TF_ThreadMgr,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&threads));result("child_runtime_thread_manager",hr);if(hr!=S_OK)return hr;
        TfClientId client=TF_CLIENTID_NULL;hr=threads->Activate(&client);result("child_runtime_thread_activate",hr);active=SUCCEEDED(hr);if(hr!=S_OK)return hr;
        hr=CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&languages));result("child_runtime_languages",hr);if(hr!=S_OK)return hr;
        hr=languages->GetCurrentLanguage(&beforeLanguage);result("child_runtime_original_language",hr);if(hr!=S_OK)return hr;
        int count=GetKeyboardLayoutList(0,nullptr);if(count<=0||count>256)return E_FAIL;
        std::vector<HKL> layouts(static_cast<size_t>(count));if(GetKeyboardLayoutList(count,layouts.data())!=count)return E_FAIL;
        HKL chinese=nullptr;for(HKL layout:layouts)if(LOWORD(reinterpret_cast<ULONG_PTR>(layout))==ZapLanguage){chinese=layout;break;}
        if(!chinese||!ActivateKeyboardLayout(chinese,0)||LOWORD(reinterpret_cast<ULONG_PTR>(GetKeyboardLayout(0)))!=ZapLanguage)return E_FAIL;
        languageAttempted=true;hr=languages->ChangeCurrentLanguage(ZapLanguage);result("child_runtime_change_language",hr);if(hr!=S_OK)return hr;
        LANGID current=0;hr=languages->GetCurrentLanguage(&current);result("child_runtime_query_language",hr);
        return hr==S_OK&&current==ZapLanguage?S_OK:E_FAIL;
    }
    bool finish() noexcept {
        if(finished)return true;finished=true;bool ok=true;
        if(languageAttempted&&languages){
            LANGID current=0;HRESULT query=languages->GetCurrentLanguage(&current);result("child_runtime_language_before_restore",query);
            if(query==S_OK&&current==beforeLanguage){
                std::cout<<"profile_probe child_runtime_language_already_original=PASS\n";
            }else{
                HRESULT hr=languages->ChangeCurrentLanguage(beforeLanguage);result("child_runtime_restore_language",hr);
                current=0;query=languages->GetCurrentLanguage(&current);result("child_runtime_language_after_restore",query);
                ok=hr==S_OK&&query==S_OK&&current==beforeLanguage&&ok;
            }
        }
        if(beforeLayout){ok=ActivateKeyboardLayout(beforeLayout,0)!=nullptr&&GetKeyboardLayout(0)==beforeLayout&&ok;beforeLayout=nullptr;}
        if(active&&threads){HRESULT hr=threads->Deactivate();result("child_runtime_thread_deactivate",hr);ok=hr==S_OK&&ok;active=false;}
        languages.Reset();threads.Reset();ok=focus.closeFocusWindow()&&ok;std::cout<<"profile_probe child_runtime_cleanup="<<(ok?"PASS":"FAIL")<<"\n";return ok;
    }
    ~QueryRuntime(){finish();}
};
static int queryOnly(bool prepareRuntime){

    // --query-only retains the original read-only baseline. --query-runtime
    // initializes transient TSF/language state in this fresh process and restores
    // it. Windows may load a default service; no profile registration or explicit
    // ZapIME activation occurs here, and the parent owns temporary data cleanup.
    HRESULT init=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);result("child_com_init",init);if(FAILED(init))return 1;
    int code=1;
    {
        QueryRuntime runtime;bool runtimeReady=!prepareRuntime||runtime.begin()==S_OK;
        std::cout<<"profile_probe child_mode_prepared="<<(prepareRuntime?1:0)<<" runtime_ready="<<(runtimeReady?1:0)<<"\n";
        {
        ComPtr<ITfInputProcessorProfileMgr> profiles;ComPtr<ITfCategoryMgr> categories;
        HRESULT p=CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&profiles));result("child_profiles",p);
        HRESULT c=CoCreateInstance(CLSID_TF_CategoryMgr,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&categories));result("child_categories",c);
        const std::wstring id=ZapClsidText;
        registryPresence("child_hkcu_com",HKEY_CURRENT_USER,L"Software\\Classes\\CLSID\\"+id);
        registryPresence("child_hklm_com",HKEY_LOCAL_MACHINE,L"Software\\Classes\\CLSID\\"+id);
        registryPresence("child_hkcu_tip",HKEY_CURRENT_USER,L"Software\\Microsoft\\CTF\\TIP\\"+id);
        registryPresence("child_hklm_tip",HKEY_LOCAL_MACHINE,L"Software\\Microsoft\\CTF\\TIP\\"+id);
        // Check only our exact language/profile keys, without exposing string values.
        const std::wstring languageProfile=L"Software\\Microsoft\\CTF\\TIP\\"+id+L"\\LanguageProfile\\0x00000804\\"+std::wstring(L"{042DCDBD-B1DC-4A0D-931C-567221A172E8}");
        registryPresence("child_hkcu_language_profile",HKEY_CURRENT_USER,languageProfile);
        registryPresence("child_hklm_language_profile",HKEY_LOCAL_MACHINE,languageProfile);
        registryPresence("child_merged_com",HKEY_CLASSES_ROOT,L"CLSID\\"+id);
        ComPtr<ITfInputProcessorProfiles> legacy;
        HRESULT legacyHr=CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&legacy));result("child_legacy_profiles",legacyHr);
        if(legacyHr==S_OK){
            LANGID currentLanguage=0;result("child_current_language",legacy->GetCurrentLanguage(&currentLanguage));
            std::cout<<"profile_probe child_current_language_id=0x"<<std::hex<<currentLanguage<<std::dec<<"\n";
            BSTR description=nullptr;HRESULT descriptionHr=legacy->GetLanguageProfileDescription(ZapClsid,ZapLanguage,ZapProfile,&description);
            result("child_legacy_description",descriptionHr);std::cout<<"profile_probe child_description_nonempty="<<(description&&SysStringLen(description)>0?1:0)<<"\n";SysFreeString(description);
            BOOL enabled=FALSE;HRESULT enabledHr=legacy->IsEnabledLanguageProfile(ZapClsid,ZapLanguage,ZapProfile,&enabled);
            result("child_legacy_enabled",enabledHr);std::cout<<"profile_probe child_legacy_enabled_value="<<(enabled?1:0)<<"\n";
            LANGID *languages=nullptr;ULONG count=0;HRESULT languagesHr=legacy->GetLanguageList(&languages,&count);
            result("child_language_list",languagesHr);bool available=false;
            if(languagesHr==S_OK&&languages&&count<=256)for(ULONG i=0;i<count;++i)available=available||languages[i]==ZapLanguage;
            std::cout<<"profile_probe child_language_count="<<count<<" bounded="<<(count<=256?1:0)<<" target_language_present="<<(available?1:0)<<"\n";CoTaskMemFree(languages);
        }
        bool member=false;HRESULT category=E_FAIL,profile=E_FAIL;TF_INPUTPROCESSORPROFILE value{};
        if(p==S_OK)profile=profiles->GetProfile(TF_PROFILETYPE_INPUTPROCESSOR,ZapLanguage,ZapClsid,ZapProfile,nullptr,&value);
        result("child_query_registered",profile);
        if(profile==S_OK)std::cout<<"profile_probe child_identity type="<<value.dwProfileType<<" lang=0x"<<std::hex<<value.langid<<" flags=0x"<<value.dwFlags<<std::dec
                                <<" clsid="<<guidText(value.clsid)<<" profile="<<guidText(value.guidProfile)<<" category="<<guidText(value.catid)<<"\n";
        if(c==S_OK)category=keyboardCategory(categories.Get(),member);
        result("child_keyboard_category",category);std::cout<<"profile_probe child_keyboard_member="<<(member?1:0)<<"\n";
        if(runtimeReady&&profile==S_OK&&value.dwProfileType==TF_PROFILETYPE_INPUTPROCESSOR&&value.langid==ZapLanguage&&value.clsid==ZapClsid&&value.guidProfile==ZapProfile
           &&(value.dwFlags&TF_IPP_FLAG_ENABLED)!=0&&category==S_OK&&member)code=0;
        }
        if(!runtime.finish())code=1;
    }
    CoUninitialize();std::cout<<"profile_probe child_query_result="<<(code==0?"PASS":"FAIL")<<"\n";return code;
}
static bool queryFreshProcess(){
    wchar_t executable[MAX_PATH]{};DWORD length=GetModuleFileNameW(nullptr,executable,MAX_PATH);require(length>0&&length<MAX_PATH,"child_executable_path");
    std::wstring command=L"\""+std::wstring(executable)+L"\" --query-runtime";
    STARTUPINFOW start{};start.cb=sizeof(start);start.dwFlags=STARTF_USESTDHANDLES;
    start.hStdInput=GetStdHandle(STD_INPUT_HANDLE);start.hStdOutput=GetStdHandle(STD_OUTPUT_HANDLE);start.hStdError=GetStdHandle(STD_ERROR_HANDLE);
    PROCESS_INFORMATION child{};std::cout.flush();std::cerr.flush();
    BOOL created=CreateProcessW(executable,command.data(),nullptr,nullptr,TRUE,CREATE_NO_WINDOW,nullptr,nullptr,&start,&child);
    std::cout<<"profile_probe child_started="<<(created?1:0)<<" error="<<(created?0:GetLastError())<<"\n";if(!created)return false;
    CloseHandle(child.hThread);DWORD exitCode=1;bool finished=false,quit=false;
    ULONGLONG deadline=GetTickCount64()+15000;
    while(GetTickCount64()<deadline){
        ULONGLONG now=GetTickCount64();if(now>=deadline)break;
        DWORD remaining=static_cast<DWORD>(deadline-now);if(remaining>250)remaining=250;
        DWORD waited=MsgWaitForMultipleObjectsEx(1,&child.hProcess,remaining,QS_ALLINPUT,MWMO_INPUTAVAILABLE);
        if(waited==WAIT_OBJECT_0){finished=GetExitCodeProcess(child.hProcess,&exitCode)!=FALSE;break;}
        if(waited==WAIT_FAILED)break;
        // The parent owns a window. Dispatch its bounded message batches while
        // the independent child requests ordinary foreground/focus transitions.
        MSG message{};for(int count=0;count<64&&PeekMessageW(&message,nullptr,0,0,PM_REMOVE);++count){
            if(message.message==WM_QUIT){quit=true;break;}TranslateMessage(&message);DispatchMessageW(&message);
        }
        if(quit)break;
    }
    // The child cannot mutate profiles. Terminating this bounded read-only query
    // does not bypass the parent's mandatory registration rollback.
    if(!finished){TerminateProcess(child.hProcess,1);WaitForSingleObject(child.hProcess,2000);}
    CloseHandle(child.hProcess);std::cout<<"profile_probe child_finished="<<(finished?1:0)<<" exit="<<exitCode<<"\n";return finished&&exitCode==0;
}
struct Probe {
    bool com=false,owned=false,threadActive=false,activationAttempted=false,loadedLayout=false,haveBefore=false,ownsData=false;
    OwnedFocusWindow focus;
    HMODULE dll=nullptr;
    HKL beforeLayout=nullptr,chineseLayout=nullptr;
    Registration unregisterProfile=nullptr;
    ComPtr<ITfInputProcessorProfileMgr> profiles;
    ComPtr<ITfCategoryMgr> categories;
    ComPtr<ITfThreadMgr> threads;
    TF_INPUTPROCESSORPROFILE before{};
    fs::path data;
    const std::wstring key=std::wstring(L"Software\\Classes\\CLSID\\")+ZapClsidText;
    HRESULT reopenManagers() noexcept {
        profiles.Reset();categories.Reset();CoFreeUnusedLibrariesEx(0,0);
        HRESULT hr=CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&profiles));if(hr!=S_OK)return hr;
        return CoCreateInstance(CLSID_TF_CategoryMgr,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&categories));
    }
    bool cleanup() noexcept {
        bool ok=true;
        if(activationAttempted&&profiles){HRESULT hr=profiles->DeactivateProfile(TF_PROFILETYPE_INPUTPROCESSOR,ZapLanguage,ZapClsid,ZapProfile,nullptr,TF_IPPMF_FORPROCESS);result("deactivate",hr);ok=SUCCEEDED(hr)&&ok;activationAttempted=false;}
        if(beforeLayout){ok=ActivateKeyboardLayout(beforeLayout,0)!=nullptr&&ok;beforeLayout=nullptr;}
        if(haveBefore&&profiles){HRESULT hr=profiles->ActivateProfile(before.dwProfileType,before.langid,before.clsid,before.guidProfile,before.hkl,TF_IPPMF_FORPROCESS);result("restore_previous",hr);ok=hr==S_OK&&ok;haveBefore=false;}
        if(threadActive&&threads){HRESULT hr=threads->Deactivate();result("thread_deactivate",hr);ok=hr==S_OK&&ok;threadActive=false;}
        threads.Reset();
        if(owned&&unregisterProfile){
            HRESULT hr=unregisterProfile();result("unregister",hr);ok=hr==S_OK&&ok;
            HRESULT fresh=reopenManagers();result("fresh_managers_after_unregister",fresh);ok=fresh==S_OK&&ok;
            if(fresh==S_OK){
                TF_INPUTPROCESSORPROFILE removed{};hr=profiles->GetProfile(TF_PROFILETYPE_INPUTPROCESSOR,ZapLanguage,ZapClsid,ZapProfile,nullptr,&removed);result("query_after_unregister",hr);ok=hr!=S_OK&&absentKey(key)&&ok;
                bool categoryPresent=false;HRESULT categoryHr=keyboardCategory(categories.Get(),categoryPresent);result("query_category_after_unregister",categoryHr);ok=categoryHr==S_OK&&!categoryPresent&&ok;
            }
            owned=false;
        }
        profiles.Reset();categories.Reset();
        bool focusRestored=focus.closeFocusWindow();std::cout<<"profile_probe owned_window_cleanup="<<(focusRestored?"PASS":"FAIL")<<"\n";ok=focusRestored&&ok;
        if(dll){FreeLibrary(dll);dll=nullptr;}
        if(com){CoFreeUnusedLibrariesEx(0,0);CoUninitialize();com=false;}
        if(loadedLayout&&chineseLayout){ok=UnloadKeyboardLayout(chineseLayout)!=FALSE&&ok;loadedLayout=false;}
        if(ownsData){std::error_code error;fs::remove_all(data,error);ok=!error&&ok;ownsData=false;}
        std::cout<<"profile_probe cleanup="<<(ok?"PASS":"FAIL")<<"\n";
        return ok;
    }
};
int main(int argc,char **argv){
    Probe probe;int code=1;
    try{
        // This executable is not part of local ctest/build_windows execution.
        require(env(L"GITHUB_ACTIONS")==L"true"&&env(L"RUNNER_ENVIRONMENT")==L"github-hosted"&&digits(env(L"GITHUB_RUN_ID"))&&digits(env(L"GITHUB_RUN_ATTEMPT")),"dedicated_github_hosted_runner_required");
        require(argc==1||(argc==2&&(std::string(argv[1])=="--query-only"||std::string(argv[1])=="--query-runtime")),"invalid_probe_arguments");
        environmentSummary();
        if(argc==2)return queryOnly(std::string(argv[1])=="--query-runtime");
        HRESULT comHr=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);probe.com=SUCCEEDED(comHr);checked("com_init",comHr);
        checked("profiles",CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&probe.profiles)));
        checked("category_manager",CoCreateInstance(CLSID_TF_CategoryMgr,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&probe.categories)));
        bool categoryPresent=false;checked("category_before",keyboardCategory(probe.categories.Get(),categoryPresent));require(!categoryPresent,"refuse_existing_zapime_category");
        TF_INPUTPROCESSORPROFILE existing{};HRESULT existingHr=probe.profiles->GetProfile(TF_PROFILETYPE_INPUTPROCESSOR,ZapLanguage,ZapClsid,ZapProfile,nullptr,&existing);
        require(existingHr!=S_OK&&absentKey(probe.key)&&absentAt(HKEY_LOCAL_MACHINE,probe.key)
                &&absentAt(HKEY_CURRENT_USER,std::wstring(L"Software\\Microsoft\\CTF\\TIP\\")+ZapClsidText)
                &&absentAt(HKEY_LOCAL_MACHINE,std::wstring(L"Software\\Microsoft\\CTF\\TIP\\")+ZapClsidText),"refuse_existing_zapime_registration");
        PWSTR appData=nullptr;checked("local_appdata",SHGetKnownFolderPath(FOLDERID_LocalAppData,0,nullptr,&appData));probe.data=fs::path(appData)/L"ZapIME";CoTaskMemFree(appData);
        require(!fs::exists(probe.data),"refuse_existing_zapime_data");
        probe.ownsData=true; // Reserved only after proving absence on this disposable runner.
        wchar_t module[MAX_PATH]{};DWORD length=GetModuleFileNameW(nullptr,module,MAX_PATH);require(length>0&&length<MAX_PATH,"executable_path");
        fs::path path=fs::path(module).parent_path()/L"ZapIMETsf.dll";
        probe.dll=LoadLibraryExW(path.c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);require(probe.dll!=nullptr,"load_exact_sibling_dll");
        auto install=reinterpret_cast<Registration>(GetProcAddress(probe.dll,"DllRegisterServer"));probe.unregisterProfile=reinterpret_cast<Registration>(GetProcAddress(probe.dll,"DllUnregisterServer"));require(install&&probe.unregisterProfile,"registration_exports");
        // Windows 8+ LoadKeyboardLayout requires a window with keyboard focus.
        // Prepare the real runtime locale before querying the new profile.
        probe.beforeLayout=GetKeyboardLayout(0);require(probe.beforeLayout!=nullptr,"initial_thread_layout");
        probe.focus.createFocusWindow();
        checked("thread_manager",CoCreateInstance(CLSID_TF_ThreadMgr,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&probe.threads)));TfClientId client=TF_CLIENTID_NULL;checked("thread_activate",probe.threads->Activate(&client));probe.threadActive=true;
        HRESULT beforeHr=probe.profiles->GetActiveProfile(GUID_TFCAT_TIP_KEYBOARD,&probe.before);result("query_previous",beforeHr);probe.haveBefore=beforeHr==S_OK;
        int count=GetKeyboardLayoutList(0,nullptr);require(count>0&&count<=256,"keyboard_layout_count");std::vector<HKL> layouts(static_cast<size_t>(count));require(GetKeyboardLayoutList(count,layouts.data())==count,"keyboard_layout_snapshot");
        for(HKL layout:layouts)if(LOWORD(reinterpret_cast<ULONG_PTR>(layout))==ZapLanguage){probe.chineseLayout=layout;break;}
        if(!probe.chineseLayout){probe.chineseLayout=LoadKeyboardLayoutW(L"00000804",KLF_NOTELLSHELL);probe.loadedLayout=probe.chineseLayout!=nullptr&&std::find(layouts.begin(),layouts.end(),probe.chineseLayout)==layouts.end();}
        require(probe.chineseLayout!=nullptr&&LOWORD(reinterpret_cast<ULONG_PTR>(probe.chineseLayout))==ZapLanguage,"chinese_layout_available");
        require(ActivateKeyboardLayout(probe.chineseLayout,0)!=nullptr&&LOWORD(reinterpret_cast<ULONG_PTR>(GetKeyboardLayout(0)))==ZapLanguage,"thread_language_matches");
        std::cout<<"profile_probe chinese_runtime_layout_ready=PASS newly_loaded="<<(probe.loadedLayout?1:0)<<"\n";
        // Own cleanup before calling an installer that can partially fail.
        probe.owned=true;checked("register",install());
        // Diagnose actual system COM resolution before a later profile-query
        // failure can hide it. Still require this result at the original gate.
        ComPtr<ITfTextInputProcessorEx> systemProcessor;
        HRESULT systemComHr=CoCreateInstance(ZapClsid,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&systemProcessor));
        result("registered_com_resolution",systemComHr);systemProcessor.Reset();
        // Registration ran through different COM instances inside the DLL.
        // Query with fresh managers after releasing prior enumerator caches.
        bool childVisible=queryFreshProcess();
        checked("fresh_managers_after_register",probe.reopenManagers());
        TF_INPUTPROCESSORPROFILE registered{};HRESULT registeredHr=probe.profiles->GetProfile(TF_PROFILETYPE_INPUTPROCESSOR,ZapLanguage,ZapClsid,ZapProfile,nullptr,&registered);result("query_registered",registeredHr);
        require(childVisible,"fresh_process_profile_and_category_visible");require(registeredHr==S_OK,"query_registered");
        std::cout<<"profile_probe queried type="<<registered.dwProfileType<<" lang=0x"<<std::hex<<registered.langid<<" flags=0x"<<registered.dwFlags<<std::dec<<" clsid="<<guidText(registered.clsid)<<" profile="<<guidText(registered.guidProfile)<<" category="<<guidText(registered.catid)<<"\n";
        require(registered.dwProfileType==TF_PROFILETYPE_INPUTPROCESSOR&&registered.langid==ZapLanguage&&registered.clsid==ZapClsid&&registered.guidProfile==ZapProfile,"registered_identity");
        // This runner returns GUID_NULL in GetProfile.catid. Verify actual
        // registration through the category manager rather than assume that
        // optional-looking returned field describes its current membership.
        checked("enumerate_keyboard_category",keyboardCategory(probe.categories.Get(),categoryPresent));require(categoryPresent,"registered_keyboard_category_membership");
        require((registered.dwFlags&TF_IPP_FLAG_ENABLED)!=0,"registered_enabled");
        wchar_t registeredPath[MAX_PATH]{};DWORD pathBytes=sizeof(registeredPath);
        require(RegGetValueW(HKEY_CURRENT_USER,(probe.key+L"\\InprocServer32").c_str(),nullptr,RRF_RT_REG_SZ,nullptr,registeredPath,&pathBytes)==ERROR_SUCCESS&&fs::equivalent(fs::path(registeredPath),path),"registered_com_path_is_exact_dll");
        checked("registered_com_object",systemComHr);
        std::cout<<"profile_probe registered_identity_category_enabled=PASS\n";
        // No FORSESSION / default-profile update / deferred activation flag.
        probe.activationAttempted=true;checked("activate_for_process",probe.profiles->ActivateProfile(TF_PROFILETYPE_INPUTPROCESSOR,ZapLanguage,ZapClsid,ZapProfile,nullptr,TF_IPPMF_FORPROCESS));
        TF_INPUTPROCESSORPROFILE active{};checked("query_active",probe.profiles->GetActiveProfile(GUID_TFCAT_TIP_KEYBOARD,&active));
        require(active.dwProfileType==TF_PROFILETYPE_INPUTPROCESSOR&&active.clsid==ZapClsid&&active.guidProfile==ZapProfile&&active.langid==ZapLanguage&&(active.dwFlags&TF_IPP_FLAG_ACTIVE)!=0,"active_profile_is_zapime");
        std::cout<<"PASS process-local TSF profile registration and activation; NOT application typing\n";code=0;
    }catch(const std::exception &e){std::cerr<<"FAIL profile_probe "<<e.what()<<"\n";}
    if(!probe.cleanup())code=1;
    return code;
}
