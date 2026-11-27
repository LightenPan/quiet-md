//! Mobile platform code: the iOS/iPadOS port's seam (docs/design/mobile.md).
//! The editor, home and sidebar are the same GPUI views the desktop uses —
//! only the platform shell goes here: input-method and soft-keyboard
//! adaptation, touch selection, safe areas, and calls into the thin native
//! shell. Nothing yet: the spike (docs/design/mobile-spike.md) confirmed the
//! route with vendor patches; this is where its application-side follow-ups
//! land.
