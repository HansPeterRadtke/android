rm app/build/outputs/apk/debug/app-debug.apk
gradle assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk /var/www/html/explorer/upload

