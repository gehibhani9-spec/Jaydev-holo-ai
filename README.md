# Holo: voice-controlled phone agent

- Holographic 3D interface (drag to spin it)
- Full phone control through an Accessibility Service (opens apps, taps, types, scrolls)
- Tasks: say "add task buy milk", "show tasks", "clear tasks"
- Free talk mode: say "change to free talk mode" (chat is saved), "change to normal" to go back
- Free talk replies are savage and sarcastic

## Build the APK (no computer needed)
1. Create an empty GitHub repo.
2. In Termux, inside this folder:
   git init && git add . && git commit -m "holo"
   git branch -M main
   git remote add origin https://github.com/YOUR_NAME/YOUR_REPO.git
   git push -u origin main
3. On GitHub: Actions tab > "Build APK" > open the finished run > download "holo-apk".
4. Unzip, install app-debug.apk (allow "install unknown apps").

## First run
1. Tap KEY and paste a free Gemini API key (aistudio.google.com).
2. Tap ACCESS, find Holo, switch it on.
3. Tap MIC or type: "open WhatsApp and message Rahul hi".
