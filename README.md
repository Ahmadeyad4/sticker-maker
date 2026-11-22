# Sticker Maker

Android app (Kotlin + Jetpack Compose) that builds WhatsApp sticker packs
from your images and adds them to WhatsApp.

## Features
- Static packs: any image becomes a 512x512 WebP under 100KB (auto quality loop)
- Tray icon (96x96 PNG) generated automatically
- Adds the pack through WhatsApp's official ContentProvider + Intent contract
- Animated packs: UI is ready, but you must plug in an encoder (below)

## Run
1. Open the folder in Android Studio (it generates the Gradle wrapper).
2. Run on a real device with WhatsApp installed.

## WhatsApp rules
- 3 to 30 stickers per pack
- A pack is either all static or all animated (no mixing)
- Static: 512x512 WebP, <= 100KB. Animated: 512x512 animated WebP, <= 500KB, <= 10s
- Tray icon: 96x96, <= 50KB

## Animated stickers
Android has no animated WebP encoder built in. Implement `AnimatedEncoder`
with FFmpeg (use a maintained FFmpeg Android build; the original ffmpeg-kit
was retired) and register it:

    StickerConverter.animatedEncoder = MyFfmpegEncoder()

Command to run inside your encoder:

    ffmpeg -i in.mp4 -t 10 -vf "fps=15,scale=512:512:force_original_aspect_ratio=decrease,pad=512:512:(ow-iw)/2:(oh-ih)/2:color=0x00000000" -loop 0 -c:v libwebp -quality 50 out.webp

If the output exceeds 500KB, retry with lower `-quality` or `fps`.
The tray icon for animated packs must be a still image (extract one frame).

## Structure
    app/src/main/java/com/example/stickermaker/
      MainActivity.kt            UI + add-to-WhatsApp intent
      StickerConverter.kt        image conversion + AnimatedEncoder hook
      StickerContentProvider.kt  WhatsApp contract
      StickerStore.kt            pack storage
