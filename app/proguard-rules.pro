# необязательные части библиотек, которых в программе нет
-dontwarn org.slf4j.**
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.**
-dontwarn org.brotli.**
-dontwarn org.tukaani.**
-dontwarn org.objectweb.asm.**
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# связь ADB и шифрование
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-keep class com.github.junrar.** { *; }
-keep class org.apache.commons.compress.archivers.** { *; }
-keep class org.apache.commons.compress.compressors.** { *; }
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-dontwarn javax.servlet.**
-dontwarn org.bouncycastle.**
# SMB с логином и паролем (15.0): NTLM берёт MD4 у провайдера BouncyCastle по имени; без этих правил R8 убирает MD4, и вход по паролю в release не работает
-keep class org.bouncycastle.jcajce.provider.digest.MD4** { *; }
-keep class org.bouncycastle.crypto.digests.MD4Digest { *; }
