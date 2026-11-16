# Optimization settings
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively

# PDFBox-Android: only page-tree edits and image embedding are used, so R8 can
# strip the rest. These optional dependencies are referenced but never shipped.
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.stream.**

# The password handler is created reflectively when a protected PDF is opened.
-keep class com.tom_roush.pdfbox.pdmodel.encryption.StandardSecurityHandler { <init>(...); }
-keep class com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy { <init>(...); }
