// Apple's linker has no `-Wl,--wrap`. On macOS every translation unit is compiled with
// `-DSDL_X=__wrap_SDL_X` (see CMakeLists.txt), so callers reach the wraps that
// sdl_intercepts.cpp defines; this file, compiled without those renames, provides the
// `__real_SDL_X` symbols the wraps forward to.
#undef SDL_PollEvent
#undef SDL_UpdateTexture
#undef SDL_RenderCopy
#undef SDL_RenderPresent
#undef SDL_QueueAudio
#undef SDL_OpenAudioDevice
#include <SDL.h>

int __real_SDL_PollEvent(SDL_Event* e) { return SDL_PollEvent(e); }
int __real_SDL_UpdateTexture(SDL_Texture* t, const SDL_Rect* r, const void* p, int pitch) {
    return SDL_UpdateTexture(t, r, p, pitch);
}
int __real_SDL_RenderCopy(SDL_Renderer* rd, SDL_Texture* t, const SDL_Rect* s, const SDL_Rect* d) {
    return SDL_RenderCopy(rd, t, s, d);
}
void __real_SDL_RenderPresent(SDL_Renderer* rd) { SDL_RenderPresent(rd); }
int __real_SDL_QueueAudio(SDL_AudioDeviceID dev, const void* data, Uint32 len) {
    return SDL_QueueAudio(dev, data, len);
}
SDL_AudioDeviceID __real_SDL_OpenAudioDevice(const char* device, int iscapture, const SDL_AudioSpec* desired,
                                             SDL_AudioSpec* obtained, int allowed_changes) {
    return SDL_OpenAudioDevice(device, iscapture, desired, obtained, allowed_changes);
}
