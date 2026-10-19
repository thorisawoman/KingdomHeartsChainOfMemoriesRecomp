#pragma once
// Bottom-screen companion (Android dual-screen devices such as the AYN Thor).
//
// The Java side (android/app/src/main/java/org/khcomrecomp/) shows a Presentation on the
// secondary display. It never touches guest state directly: menu requests are queued here and
// applied on the game thread, and guest RAM is read only on the game thread into a snapshot
// that Java polls. Elsewhere these are no-ops.

namespace khcom::dual_screen {

// Called from the SDL poll hook on the game thread, once per frame.
#if defined(__ANDROID__)
void on_poll();
#else
inline void on_poll() {}
#endif

} // namespace khcom::dual_screen
