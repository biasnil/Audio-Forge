#include "audioforge/windows_integration.hpp"
#include "audioforge/theme.hpp"
#include "audioforge/warnings.hpp"

#include <QWidget>

#ifdef Q_OS_WIN

// The GUIDs these headers declare are defined here (selectany: no clashes).
#include <initguid.h>

#include <windows.h>
#include <objidl.h>
#include <roapi.h>
#include <shcore.h>
#include <shlwapi.h>
#include <shobjidl.h>
#include <systemmediatransportcontrolsinterop.h>
#include <windows.media.h>
#include <windows.storage.streams.h>
#include <winstring.h>

#include <QCoreApplication>
#include <QImage>
#include <QPointer>

#include <atomic>

namespace audioforge {

namespace {

namespace Media = ABI::Windows::Media;
namespace Streams = ABI::Windows::Storage::Streams;

// Releases a COM pointer when it goes out of scope.
template <typename T>
struct Com
{
    T* p = nullptr;
    Com() = default;
    Com(const Com&) = delete;
    Com& operator=(const Com&) = delete;
    ~Com() { reset(); }
    void reset()
    {
        if (p)
        {
            p->Release();
            p = nullptr;
        }
    }
    T** put()
    {
        reset();
        return &p;
    }
    T* operator->() const { return p; }
    explicit operator bool() const { return p != nullptr; }
};

// A Windows Runtime string for the length of one call.
class HString
{
public:
    explicit HString(const QString& text)
    {
        const std::wstring wide = text.toStdWString();
        WindowsCreateString(wide.c_str(), static_cast<UINT32>(wide.size()), &m_string);
    }
    HString(const wchar_t* text, UINT32 length) { WindowsCreateString(text, length, &m_string); }
    ~HString() { WindowsDeleteString(m_string); }
    HString(const HString&) = delete;
    HString& operator=(const HString&) = delete;
    HSTRING get() const { return m_string; }

private:
    HSTRING m_string = nullptr;
};

using ButtonHandlerBase = ABI::Windows::Foundation::ITypedEventHandler<Media::SystemMediaTransportControls*,
                                                                      Media::SystemMediaTransportControlsButtonPressedEventArgs*>;

// Receives the media controls' button presses -- on a Windows thread, so
// they're handed to the Qt thread as queued calls.
class ButtonHandler : public ButtonHandlerBase, public IAgileObject
{
public:
    explicit ButtonHandler(WindowsMediaControls* owner) : m_owner(owner) {}

    void detach() { m_owner.store(nullptr); }

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** object) override
    {
        if (riid == IID_IUnknown
            || riid == IID___FITypedEventHandler_2_Windows__CMedia__CSystemMediaTransportControls_Windows__CMedia__CSystemMediaTransportControlsButtonPressedEventArgs)
        {
            *object = static_cast<ButtonHandlerBase*>(this);
        }
        else if (riid == IID_IAgileObject)
        {
            *object = static_cast<IAgileObject*>(this);
        }
        else
        {
            *object = nullptr;
            return E_NOINTERFACE;
        }
        AddRef();
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++m_refs; }
    ULONG STDMETHODCALLTYPE Release() override
    {
        const ULONG refs = --m_refs;
        if (refs == 0)
        {
            delete this;
        }
        return refs;
    }

    HRESULT STDMETHODCALLTYPE Invoke(Media::ISystemMediaTransportControls*,
                                     Media::ISystemMediaTransportControlsButtonPressedEventArgs* args) override
    {
        Media::SystemMediaTransportControlsButton button;
        if (!args || FAILED(args->get_Button(&button)))
        {
            return S_OK;
        }
        WindowsMediaControls* owner = m_owner.load();
        if (!owner)
        {
            return S_OK;
        }
        QPointer<WindowsMediaControls> guard(owner);
        QMetaObject::invokeMethod(owner, [guard, button]() {
            if (!guard)
            {
                return;
            }
            switch (button)
            {
                case Media::SystemMediaTransportControlsButton_Play:     emit guard->playRequested(); break;
                case Media::SystemMediaTransportControlsButton_Pause:    emit guard->pauseRequested(); break;
                case Media::SystemMediaTransportControlsButton_Next:     emit guard->nextRequested(); break;
                case Media::SystemMediaTransportControlsButton_Previous: emit guard->previousRequested(); break;
                default: break;
            }
        }, Qt::QueuedConnection);
        return S_OK;
    }

private:
    std::atomic<ULONG> m_refs{1};
    std::atomic<WindowsMediaControls*> m_owner;
};

// Taskbar thumbnail button ids.
constexpr UINT kPreviousButton = 1;
constexpr UINT kPlayPauseButton = 2;
constexpr UINT kNextButton = 3;

HICON MakeIcon(theme::Icon kind)
{
    // White glyphs: the thumbnail preview is always dark.
    const QIcon icon = theme::icon(kind, QColor(255, 255, 255), QColor(), QColor(), 16);
    return icon.pixmap(QSize(16, 16), 2.0).toImage().scaled(32, 32, Qt::KeepAspectRatio, Qt::SmoothTransformation).toHICON();
}

} // namespace

struct WindowsMediaControls::Impl
{
    HWND hwnd = nullptr;
    // System media controls.
    Com<Media::ISystemMediaTransportControls> smtc;
    ButtonHandler* handler = nullptr;
    EventRegistrationToken token{};
    // Taskbar.
    UINT taskbarCreatedMessage = 0;
    Com<ITaskbarList3> taskbar;
    bool buttonsAdded = false;
    HICON previousIcon = nullptr, playIcon = nullptr, pauseIcon = nullptr, nextIcon = nullptr;
    bool hasTrack = false;
    bool playing = false;
    int lastProgressPermille = -1;
    int lastProgressState = -1;

    void addThumbButtons()
    {
        if (!taskbar || buttonsAdded)
        {
            return;
        }
        THUMBBUTTON buttons[3] = {};
        const UINT ids[3] = {kPreviousButton, kPlayPauseButton, kNextButton};
        for (int i = 0; i < 3; ++i)
        {
            buttons[i].dwMask = THB_ICON | THB_TOOLTIP | THB_FLAGS;
            buttons[i].iId = ids[i];
        }
        buttonsAdded = SUCCEEDED(taskbar->ThumbBarAddButtons(hwnd, 3, buttons));
        updateThumbButtons();
    }

    void updateThumbButtons()
    {
        if (!taskbar || !buttonsAdded)
        {
            return;
        }
        THUMBBUTTON buttons[3] = {};
        auto set = [](THUMBBUTTON& b, UINT id, HICON icon, const QString& tip, bool enabled) {
            b.dwMask = THB_ICON | THB_TOOLTIP | THB_FLAGS;
            b.iId = id;
            b.hIcon = icon;
            b.dwFlags = enabled ? THBF_ENABLED : THBF_DISABLED;
            const std::wstring text = tip.toStdWString();
            wcsncpy(b.szTip, text.c_str(), ARRAYSIZE(b.szTip) - 1);
        };
        set(buttons[0], kPreviousButton, previousIcon, QCoreApplication::translate("audioforge::PlayerWindow", "Previous"), hasTrack);
        set(buttons[1], kPlayPauseButton, playing ? pauseIcon : playIcon,
            playing ? QCoreApplication::translate("audioforge::PlayerWindow", "Pause")
                    : QCoreApplication::translate("audioforge::PlayerWindow", "Play"), hasTrack);
        set(buttons[2], kNextButton, nextIcon, QCoreApplication::translate("audioforge::PlayerWindow", "Next"), hasTrack);
        taskbar->ThumbBarUpdateButtons(hwnd, 3, buttons);
    }
};

WindowsMediaControls::WindowsMediaControls(QWidget* window) : QObject(window), d(std::make_unique<Impl>())
{
    d->hwnd = reinterpret_cast<HWND>(window->winId());

    // --- System media controls ---------------------------------------------
    Com<ISystemMediaTransportControlsInterop> interop;
    HString className(RuntimeClass_Windows_Media_SystemMediaTransportControls,
                      static_cast<UINT32>(wcslen(RuntimeClass_Windows_Media_SystemMediaTransportControls)));
    if (SUCCEEDED(RoGetActivationFactory(className.get(), IID_ISystemMediaTransportControlsInterop,
                                         reinterpret_cast<void**>(interop.put())))
        && SUCCEEDED(interop->GetForWindow(d->hwnd, IID___x_ABI_CWindows_CMedia_CISystemMediaTransportControls,
                                           reinterpret_cast<void**>(d->smtc.put()))))
    {
        d->smtc->put_IsEnabled(true);
        d->smtc->put_IsPlayEnabled(true);
        d->smtc->put_IsPauseEnabled(true);
        d->smtc->put_IsNextEnabled(true);
        d->smtc->put_IsPreviousEnabled(true);
        d->handler = new ButtonHandler(this);
        d->smtc->add_ButtonPressed(d->handler, &d->token);
    }
    else
    {
        ErrorReporter::logOnly("Windows media controls aren't available on this PC.");
    }

    // --- Taskbar -------------------------------------------------------------
    d->taskbarCreatedMessage = RegisterWindowMessageW(L"TaskbarButtonCreated");
    if (SUCCEEDED(CoCreateInstance(CLSID_TaskbarList, nullptr, CLSCTX_INPROC_SERVER, IID_ITaskbarList3,
                                   reinterpret_cast<void**>(d->taskbar.put()))))
    {
        if (FAILED(d->taskbar->HrInit()))
        {
            d->taskbar.reset();
        }
    }
    d->previousIcon = MakeIcon(theme::Icon::Previous);
    d->playIcon = MakeIcon(theme::Icon::Play);
    d->pauseIcon = MakeIcon(theme::Icon::Pause);
    d->nextIcon = MakeIcon(theme::Icon::Next);
}

WindowsMediaControls::~WindowsMediaControls()
{
    if (d->smtc && d->handler)
    {
        d->smtc->remove_ButtonPressed(d->token);
    }
    if (d->handler)
    {
        d->handler->detach();
        d->handler->Release();
    }
    for (HICON icon : {d->previousIcon, d->playIcon, d->pauseIcon, d->nextIcon})
    {
        if (icon)
        {
            DestroyIcon(icon);
        }
    }
}

void WindowsMediaControls::setTrack(const QString& title, const QString& artist, const QString& album, const QByteArray& cover)
{
    if (!d->smtc)
    {
        return;
    }
    Com<Media::ISystemMediaTransportControlsDisplayUpdater> updater;
    if (FAILED(d->smtc->get_DisplayUpdater(updater.put())))
    {
        return;
    }
    updater->put_Type(Media::MediaPlaybackType_Music);
    Com<Media::IMusicDisplayProperties> music;
    if (SUCCEEDED(updater->get_MusicProperties(music.put())))
    {
        music->put_Title(HString(title).get());
        music->put_Artist(HString(artist).get());
        Com<Media::IMusicDisplayProperties2> music2;
        if (SUCCEEDED(music->QueryInterface(IID___x_ABI_CWindows_CMedia_CIMusicDisplayProperties2,
                                            reinterpret_cast<void**>(music2.put()))))
        {
            music2->put_AlbumTitle(HString(album).get());
        }
    }

    // The cover: its bytes -> an in-memory stream -> a stream reference.
    Com<Streams::IRandomAccessStreamReference> thumbnail;
    if (!cover.isEmpty())
    {
        Com<IStream> stream;
        stream.p = SHCreateMemStream(reinterpret_cast<const BYTE*>(cover.constData()), static_cast<UINT>(cover.size()));
        Com<Streams::IRandomAccessStream> randomAccess;
        Com<Streams::IRandomAccessStreamReferenceStatics> statics;
        HString referenceClass(RuntimeClass_Windows_Storage_Streams_RandomAccessStreamReference,
                               static_cast<UINT32>(wcslen(RuntimeClass_Windows_Storage_Streams_RandomAccessStreamReference)));
        if (stream
            && SUCCEEDED(CreateRandomAccessStreamOverStream(stream.p, BSOS_DEFAULT,
                                                            IID___x_ABI_CWindows_CStorage_CStreams_CIRandomAccessStream,
                                                            reinterpret_cast<void**>(randomAccess.put())))
            && SUCCEEDED(RoGetActivationFactory(referenceClass.get(),
                                                IID___x_ABI_CWindows_CStorage_CStreams_CIRandomAccessStreamReferenceStatics,
                                                reinterpret_cast<void**>(statics.put()))))
        {
            statics->CreateFromStream(randomAccess.p, thumbnail.put());
        }
    }
    updater->put_Thumbnail(thumbnail.p); // null clears the last song's
    updater->Update();
}

void WindowsMediaControls::setPlaybackState(bool hasTrack, bool playing)
{
    d->hasTrack = hasTrack;
    d->playing = playing;
    if (d->smtc)
    {
        d->smtc->put_PlaybackStatus(!hasTrack ? Media::MediaPlaybackStatus_Stopped
                                    : playing ? Media::MediaPlaybackStatus_Playing
                                              : Media::MediaPlaybackStatus_Paused);
    }
    d->updateThumbButtons();
    d->lastProgressState = -1; // the taskbar's colour (paused: yellow) follows on the next setProgress()
}

void WindowsMediaControls::setProgress(double positionSeconds, double lengthSeconds)
{
    if (!d->taskbar)
    {
        return;
    }
    const int state = !d->hasTrack || lengthSeconds <= 0.0 ? TBPF_NOPROGRESS : d->playing ? TBPF_NORMAL : TBPF_PAUSED;
    if (state != d->lastProgressState)
    {
        d->taskbar->SetProgressState(d->hwnd, static_cast<TBPFLAG>(state));
        d->lastProgressState = state;
        d->lastProgressPermille = -1;
    }
    if (state == TBPF_NOPROGRESS)
    {
        return;
    }
    const int permille = static_cast<int>(qBound(0.0, positionSeconds / lengthSeconds, 1.0) * 1000.0);
    if (permille != d->lastProgressPermille)
    {
        d->taskbar->SetProgressValue(d->hwnd, static_cast<ULONGLONG>(permille), 1000);
        d->lastProgressPermille = permille;
    }
}

bool WindowsMediaControls::handleNativeEvent(void* message, qintptr* result)
{
    const MSG* msg = static_cast<const MSG*>(message);
    if (msg->hwnd != d->hwnd)
    {
        return false;
    }
    if (d->taskbarCreatedMessage != 0 && msg->message == d->taskbarCreatedMessage)
    {
        d->buttonsAdded = false; // Explorer restarted: add them again
        d->lastProgressState = -1;
        d->addThumbButtons();
        return false;
    }
    if (msg->message == WM_COMMAND && HIWORD(msg->wParam) == THBN_CLICKED)
    {
        switch (LOWORD(msg->wParam))
        {
            case kPreviousButton:  emit previousRequested(); break;
            case kPlayPauseButton: emit playPauseRequested(); break;
            case kNextButton:      emit nextRequested(); break;
            default: return false;
        }
        *result = 0;
        return true;
    }
    return false;
}

} // namespace audioforge

#else // not Windows: nothing to integrate with

namespace audioforge {

struct WindowsMediaControls::Impl {};

WindowsMediaControls::WindowsMediaControls(QWidget* window) : QObject(window), d(std::make_unique<Impl>()) {}
WindowsMediaControls::~WindowsMediaControls() = default;
void WindowsMediaControls::setTrack(const QString&, const QString&, const QString&, const QByteArray&) {}
void WindowsMediaControls::setPlaybackState(bool, bool) {}
void WindowsMediaControls::setProgress(double, double) {}
bool WindowsMediaControls::handleNativeEvent(void*, qintptr*) { return false; }

} // namespace audioforge

#endif
