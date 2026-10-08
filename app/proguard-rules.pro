# Argon2 from BouncyCastle is used directly, keep it intact.
-keep class org.bouncycastle.crypto.generators.Argon2BytesGenerator { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters** { *; }
-dontwarn org.bouncycastle.**
