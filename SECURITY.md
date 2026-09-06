# امنیت ارسال‌یار — v2.3

## اصل مهم درباره کپی‌برداری از APK
هیچ برنامه اندرویدی را نمی‌توان طوری ساخت که مهاجم نتواند APK را استخراج، decompile یا patch کند. بنابراین امنیت ارسال‌یار بر این فرض طراحی شده که **سورس/بایت‌کد کلاینت قابل مشاهده است**.

چیزهای حساس نباید داخل APK باشند:
- کلید JWT و secret سرور
- اطلاعات دیتابیس
- منطق تأیید اشتراک و پرداخت
- محدودیت نوبت‌های روزانه
- دسترسی‌های مدیریتی

این موارد باید در سرور تعیین شوند. در نتیجه کپی کردن APK به‌تنهایی نباید به مهاجم اجازه استفاده تجاری بدون حساب/اشتراک یا دسترسی مدیر بدهد.

## اقدامات v2.3
- R8/ProGuard در Release برای obfuscation و حذف کد/منابع بلااستفاده.
- حذف لاگ HTTP در Release؛ Authorization و body نباید لاگ شوند.
- توکن ورود با `EncryptedSharedPreferences` و Android Keystore نگهداری می‌شود.
- پشتیبانی از HTTPS در Release؛ HTTP فقط در Debug/شبیه‌ساز باز است.
- `allowBackup=false` برای جلوگیری از بکاپ ساده داده‌های حساس.
- سرور با secret پیش‌فرض اجرا نمی‌شود و `ERSALYAR_SECRET` اجباری است.
- محدودیت تعداد درخواست‌ها برای login/register/support/API.
- محدودیت اندازه بدنه درخواست‌ها.
- هدرهای امنیتی HTTP.
- نقش مدیر در سرور از دیتابیس خوانده می‌شود؛ claim قدیمی JWT برای مجوز مدیریتی قابل اعتماد نیست.
- ثبت گزارش ارسال دارای `local_id` یکتای سروری برای جلوگیری از ثبت تکراری.
- کاربر نمی‌تواند `message_id` متعلق به کاربر دیگری را در گزارش ثبت کند.
- اطلاعات اشتراک و سهمیه همچنان server-authoritative هستند.

## مرحله بعد برای انتشار واقعی
1. دامنه HTTPS و reverse proxy با TLS.
2. اجرای Flask با Gunicorn/uWSGI، نه `app.run`.
3. PostgreSQL به‌جای SQLite برای چندکاربر واقعی.
4. Redis برای rate limit مشترک بین چند worker.
5. Play Integrity برای حساس‌ترین عملیات، با اعتبارسنجی token در سرور.
6. در صورت نیاز، revocation واقعی JWT و مدیریت session/device.
7. مانیتورینگ، backup و audit log.
8. تست نفوذ API و تست patch/decompile روی Release APK.

**نکته:** R8 فقط مانع کپی‌برداری سطحی است؛ جایگزین معماری server-authoritative نیست.

## v2.5 — ضدکپی و اتصال نصب
- شناسه تصادفی هر نصب در Android Keystore-backed storage نگهداری می‌شود.
- هر درخواست احراز‌شده باید `X-Install-ID` معتبر داشته باشد و نصب برای همان حساب در سرور ثبت شده باشد.
- سقف تعداد نصب همزمان هر حساب از `ERSALYAR_MAX_DEVICES_PER_USER` کنترل می‌شود (پیش‌فرض 5).
- خروج از حساب، همان نصب را از لیست دستگاه‌های مجاز حذف می‌کند.
- مدیر می‌تواند دستگاه‌های ثبت‌شده را مشاهده و لغو کند.
- بررسی گواهی امضای APK در کلاینت وجود دارد؛ مقدار `EXPECTED_SIGNING_CERT_SHA256` باید در Release/CI با fingerprint واقعی گواهی انتشار تنظیم شود.
- این لایه ضدکپی است، نه جایگزین Play Integrity. برای انتشار نهایی، Play Integrity باید به سرور متصل و token آن در backend با Google Play بررسی شود.

## Media privacy
Media files are selected from the user's device and are not uploaded to the server by the v2.7 message model. Only local URI metadata is associated with a scheduled message. Production builds should use persistable URI permissions where supported and should never log media URIs or media contents.
