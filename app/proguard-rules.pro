# ===== 开源项目 ProGuard 配置 =====
# 本配置适用于开源项目，重点关注：
# 1. 代码压缩和资源优化
# 2. 保持类名和方法名不混淆（便于调试和开源协作）
# 3. 移除未使用的代码以减小APK体积
# 4. 保持性能优化
#
# 注意：本配置使用 proguard-android.txt 而非 proguard-android-optimize.txt
# 以避免过度混淆，保持代码可读性

# ===== 基础保持规则 =====

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# If you use Volley, uncomment the following lines:
#-keep class com.android.volley.toolbox.** { *; }
#-keep class com.android.volley.RequestQueue
#-keep class com.android.volley.Request

# If you use Gson, uncomment the following lines:
#-keep class sun.misc.Unsafe { *; }
#-keep class com.google.gson.stream.** { *; }

# If you use Retrofit, uncomment the following lines:
#-keep class retrofit2.** { *; }
#-keep interface retrofit2.** { *; }
#-dontwarn retrofit2.**

# If you use OkHttp, uncomment the following lines:
#-keep class okhttp3.** { *; }
#-keep interface okhttp3.** { *; }
#-dontwarn okhttp3.**
#-dontwarn okio.**

# Keep application classes
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class com.android.vending.licensing.ILicensingService

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keepclassmembers class * extends android.view.View {
   public <init>(android.content.Context);
   public <init>(android.content.Context, android.util.AttributeSet);
   public <init>(android.content.Context, android.util.AttributeSet, int);
   public void set*(...);
}

-keepclassmembers class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator CREATOR;
}

-keepclassmembers class **.R$* {
    public static <fields>;
}

# Keep -keepattributes Signature
-keepattributes Signature

# Keep annotations
-keepattributes *Annotation*

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep specific classes and members if using reflection
# -keep public class my.package.MyClass
# -keepclassmembers class my.package.MyClass { public <fields>; public <methods>; }

# ===== 第三方库依赖处理 =====

# AndroidX Startup 相关
-keep class androidx.startup.** { *; }
-dontwarn androidx.startup.**

# AndroidX Lifecycle 相关 - 修复ProcessLifecycleInitializer找不到的问题
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# 保持ProcessLifecycleInitializer
-keep class androidx.lifecycle.ProcessLifecycleInitializer { *; }

# AndroidX ProfileInstaller 相关 - 修复ProfileInstallerInitializer找不到的问题
-keep class androidx.profileinstaller.** { *; }
-dontwarn androidx.profileinstaller.**

# 保持ProfileInstallerInitializer
-keep class androidx.profileinstaller.ProfileInstallerInitializer { *; }

# AndroidX Emoji2 相关 - 防止EmojiCompatInitializer找不到的问题
-keep class androidx.emoji2.** { *; }
-dontwarn androidx.emoji2.**

# 保持Emoji2初始化器
-keep class androidx.emoji2.text.EmojiCompatInitializer { *; }

# OkHttp相关
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# Apache POI相关 - 忽略AWT和图像处理相关的可选依赖
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.apache.batik.**
-dontwarn com.caverock.androidsvg.**

# Saxon XML处理器 - 可选依赖
-dontwarn net.sf.saxon.**

# 其他可选依赖
-dontwarn aQute.bnd.annotation.spi.**
-dontwarn reactor.blockhound.**
-dontwarn org.codehaus.stax2.**

# javax.annotation相关 - JSR 305注解
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# OSGi框架相关 - 可选依赖
-dontwarn org.osgi.framework.**

# Retrofit2相关规则已移除 - 项目不再使用 Retrofit

# Apache HTTP Client相关 - Android已弃用
-dontwarn org.apache.http.**
-dontwarn android.net.http.**

# Log4j相关 - 可选依赖
-dontwarn org.apache.logging.log4j.**

# OpenNLP相关 - 可选依赖
-dontwarn opennlp.tools.**

# ONNX Runtime相关
-keep class ai.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn com.microsoft.onnxruntime.**

# Markwon相关
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# 保持本地LLM相关类
-keep class com.example.starlocalrag.api.** { *; }
-keep class com.example.starlocalrag.LocalLlmHandler { *; }
-keep class com.example.starlocalrag.LocalLlmHandler$** { *; }

# 保持所有应用的Fragment和Activity类
-keep class com.example.starlocalrag.*Fragment { *; }
-keep class com.example.starlocalrag.*Activity { *; }
-keep class com.example.starlocalrag.MainActivity { *; }

# 保持所有点击事件处理方法
-keepclassmembers class * {
    public void onClick(android.view.View);
    public void handle*Click*();
    public void on*Click*();
}

# 保持lambda表达式和方法引用 - 修复Release版本按钮灰色问题
-keep class * {
    *** lambda$*(...); 
}
-keepclassmembers class * {
    private static synthetic *** lambda$*(...); 
}
-dontwarn java.lang.invoke.**
-keep class java.lang.invoke.** { *; }

# 保持所有OnClickListener实现
-keep class * implements android.view.View$OnClickListener {
    public void onClick(android.view.View);
}

# Missing classes warnings suppression (generated by R8)
-dontwarn aQute.bnd.annotation.Version
-dontwarn com.aayushatharva.brotli4j.Brotli4jLoader
-dontwarn com.aayushatharva.brotli4j.decoder.DecoderJNI$Status
-dontwarn com.aayushatharva.brotli4j.decoder.DecoderJNI$Wrapper
-dontwarn com.aayushatharva.brotli4j.encoder.BrotliEncoderChannel
-dontwarn com.aayushatharva.brotli4j.encoder.Encoder$Mode
-dontwarn com.aayushatharva.brotli4j.encoder.Encoder$Parameters
-dontwarn com.ctc.wstx.shaded.msv_core.driver.textui.Driver
-dontwarn com.github.javaparser.ParseResult
-dontwarn com.github.javaparser.ParserConfiguration
-dontwarn com.github.javaparser.ast.CompilationUnit
-dontwarn com.github.javaparser.ast.Node
-dontwarn com.github.javaparser.ast.NodeList
-dontwarn com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
-dontwarn com.github.javaparser.ast.body.MethodDeclaration
-dontwarn com.github.javaparser.ast.body.Parameter
-dontwarn com.github.javaparser.ast.body.TypeDeclaration
-dontwarn com.github.javaparser.ast.expr.SimpleName
-dontwarn com.github.javaparser.ast.type.ReferenceType
-dontwarn com.github.javaparser.ast.type.Type
-dontwarn com.github.javaparser.ast.type.TypeParameter
-dontwarn com.github.javaparser.resolution.MethodUsage
-dontwarn com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration
-dontwarn com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration
-dontwarn com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration
-dontwarn com.github.javaparser.resolution.types.ResolvedType
-dontwarn com.github.javaparser.symbolsolver.model.resolution.TypeSolver
-dontwarn com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
-dontwarn com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver
-dontwarn com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
-dontwarn com.github.javaparser.utils.CollectionStrategy
-dontwarn com.github.javaparser.utils.SourceRoot
-dontwarn com.github.luben.zstd.Zstd
-dontwarn com.github.luben.zstd.ZstdInputStream
-dontwarn com.github.luben.zstd.ZstdOutputStream
-dontwarn com.google.protobuf.ExtensionRegistryLite
-dontwarn com.google.protobuf.MessageLite$Builder
-dontwarn com.google.protobuf.MessageLite
-dontwarn com.google.protobuf.MessageLiteOrBuilder
-dontwarn com.google.protobuf.Parser
-dontwarn com.google.protobuf.nano.CodedOutputByteBufferNano
-dontwarn com.google.protobuf.nano.MessageNano
-dontwarn com.jcraft.jzlib.Deflater
-dontwarn com.jcraft.jzlib.Inflater
-dontwarn com.jcraft.jzlib.JZlib$WrapperType
-dontwarn com.jcraft.jzlib.JZlib
-dontwarn com.ning.compress.BufferRecycler
-dontwarn com.ning.compress.lzf.ChunkDecoder
-dontwarn com.ning.compress.lzf.ChunkEncoder
-dontwarn com.ning.compress.lzf.LZFChunk
-dontwarn com.ning.compress.lzf.LZFEncoder
-dontwarn com.ning.compress.lzf.util.ChunkDecoderFactory
-dontwarn com.ning.compress.lzf.util.ChunkEncoderFactory
-dontwarn com.oracle.svm.core.annotate.TargetClass
-dontwarn de.rototor.pdfbox.graphics2d.PdfBoxGraphics2DFontTextDrawer
-dontwarn io.netty.handler.codec.http2.DefaultHttp2DataFrame
-dontwarn io.netty.handler.codec.http2.DefaultHttp2HeadersFrame
-dontwarn io.netty.handler.codec.http2.DefaultHttp2PingFrame
-dontwarn io.netty.handler.codec.http2.DefaultHttp2ResetFrame
-dontwarn io.netty.handler.codec.http2.EmptyHttp2Headers
-dontwarn io.netty.handler.codec.http2.Http2Connection$Endpoint
-dontwarn io.netty.handler.codec.http2.Http2Connection$Listener
-dontwarn io.netty.handler.codec.http2.Http2Connection
-dontwarn io.netty.handler.codec.http2.Http2ConnectionAdapter
-dontwarn io.netty.handler.codec.http2.Http2DataFrame
-dontwarn io.netty.handler.codec.http2.Http2Error
-dontwarn io.netty.handler.codec.http2.Http2Exception
-dontwarn io.netty.handler.codec.http2.Http2FlowController
-dontwarn io.netty.handler.codec.http2.Http2Frame
-dontwarn io.netty.handler.codec.http2.Http2FrameCodec
-dontwarn io.netty.handler.codec.http2.Http2FrameCodecBuilder
-dontwarn io.netty.handler.codec.http2.Http2FrameLogger
-dontwarn io.netty.handler.codec.http2.Http2FrameStream
-dontwarn io.netty.handler.codec.http2.Http2Headers
-dontwarn io.netty.handler.codec.http2.Http2HeadersEncoder$SensitivityDetector
-dontwarn io.netty.handler.codec.http2.Http2HeadersFrame
-dontwarn io.netty.handler.codec.http2.Http2LocalFlowController
-dontwarn io.netty.handler.codec.http2.Http2MultiplexHandler
-dontwarn io.netty.handler.codec.http2.Http2PingFrame
-dontwarn io.netty.handler.codec.http2.Http2RemoteFlowController
-dontwarn io.netty.handler.codec.http2.Http2ResetFrame
-dontwarn io.netty.handler.codec.http2.Http2SecurityUtil
-dontwarn io.netty.handler.codec.http2.Http2Settings
-dontwarn io.netty.handler.codec.http2.Http2SettingsFrame
-dontwarn io.netty.handler.codec.http2.Http2Stream
-dontwarn io.netty.handler.codec.http2.Http2StreamChannel
-dontwarn io.netty.handler.codec.http2.Http2StreamChannelBootstrap
-dontwarn io.netty.handler.codec.http2.HttpConversionUtil$ExtensionHeaderNames
-dontwarn io.netty.handler.codec.http2.HttpConversionUtil
-dontwarn io.netty.handler.logging.LogLevel
-dontwarn io.netty.handler.logging.LoggingHandler
-dontwarn io.netty.handler.ssl.CipherSuiteFilter
-dontwarn io.netty.handler.ssl.SslCloseCompletionEvent
-dontwarn io.netty.handler.ssl.SslContext
-dontwarn io.netty.handler.ssl.SslContextBuilder
-dontwarn io.netty.handler.ssl.SslHandler
-dontwarn io.netty.handler.ssl.SslProvider
-dontwarn io.netty.handler.ssl.SupportedCipherSuiteFilter
-dontwarn io.netty.handler.ssl.util.InsecureTrustManagerFactory
-dontwarn io.netty.handler.ssl.util.SimpleTrustManagerFactory
-dontwarn io.netty.handler.stream.ChunkedInput
-dontwarn io.netty.handler.timeout.IdleStateHandler
-dontwarn io.netty.handler.timeout.ReadTimeoutException
-dontwarn io.netty.handler.timeout.ReadTimeoutHandler
-dontwarn io.netty.handler.timeout.TimeoutException
-dontwarn io.netty.handler.timeout.WriteTimeoutException
-dontwarn io.netty.handler.timeout.WriteTimeoutHandler
-dontwarn java.lang.instrument.ClassFileTransformer
-dontwarn javax.script.ScriptEngine
-dontwarn javax.script.ScriptEngineManager
-dontwarn javax.xml.crypto.KeySelector
-dontwarn javax.xml.crypto.KeySelectorResult
-dontwarn javax.xml.crypto.URIDereferencer
-dontwarn javax.xml.crypto.dsig.TransformService
-dontwarn javax.xml.crypto.dsig.spec.TransformParameterSpec
-dontwarn lzma.sdk.ICodeProgress
-dontwarn lzma.sdk.lzma.Encoder
-dontwarn net.jpountz.lz4.LZ4Compressor
-dontwarn net.jpountz.lz4.LZ4Exception
-dontwarn net.jpountz.lz4.LZ4Factory
-dontwarn net.jpountz.lz4.LZ4FastDecompressor
-dontwarn net.jpountz.xxhash.XXHash32
-dontwarn net.jpountz.xxhash.XXHashFactory
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.maven.plugin.AbstractMojo
-dontwarn org.apache.maven.plugins.annotations.LifecyclePhase
-dontwarn org.apache.maven.plugins.annotations.Mojo
-dontwarn org.apache.tools.ant.taskdefs.MatchingTask
-dontwarn org.apache.xml.security.Init
-dontwarn org.brotli.dec.BrotliInputStream
-dontwarn org.jboss.marshalling.ByteInput
-dontwarn org.jboss.marshalling.ByteOutput
-dontwarn org.jboss.marshalling.Marshaller
-dontwarn org.jboss.marshalling.MarshallerFactory
-dontwarn org.jboss.marshalling.MarshallingConfiguration
-dontwarn org.jboss.marshalling.Unmarshaller
-dontwarn org.jetbrains.annotations.Async$Execute
-dontwarn org.jetbrains.annotations.Async$Schedule
-dontwarn org.objectweb.asm.AnnotationVisitor
-dontwarn org.objectweb.asm.Attribute
-dontwarn org.objectweb.asm.ClassReader
-dontwarn org.objectweb.asm.ClassVisitor
-dontwarn org.objectweb.asm.FieldVisitor
-dontwarn org.objectweb.asm.MethodVisitor
-dontwarn org.openxmlformats.schemas.officeDocument.x2006.docPropsVTypes.STCy
-dontwarn org.openxmlformats.schemas.officeDocument.x2006.docPropsVTypes.STError
-dontwarn org.osgi.util.tracker.ServiceTrackerCustomizer
-dontwarn org.tukaani.xz.ARMOptions
-dontwarn org.tukaani.xz.ARMThumbOptions
-dontwarn org.tukaani.xz.DeltaOptions
-dontwarn org.tukaani.xz.FilterOptions
-dontwarn org.tukaani.xz.FinishableOutputStream
-dontwarn org.tukaani.xz.FinishableWrapperOutputStream
-dontwarn org.tukaani.xz.IA64Options
-dontwarn org.tukaani.xz.LZMA2Options
-dontwarn org.tukaani.xz.LZMAOutputStream
-dontwarn org.tukaani.xz.MemoryLimitException
-dontwarn org.tukaani.xz.PowerPCOptions
-dontwarn org.tukaani.xz.SPARCOptions
-dontwarn org.tukaani.xz.UnsupportedOptionsException
-dontwarn org.tukaani.xz.X86Options
-dontwarn org.tukaani.xz.XZ
-dontwarn org.tukaani.xz.XZOutputStream
-dontwarn org.w3c.dom.events.Event
-dontwarn org.w3c.dom.events.EventListener
-dontwarn org.w3c.dom.events.EventTarget
-dontwarn org.w3c.dom.events.MutationEvent
-dontwarn org.w3c.dom.svg.SVGDocument
-dontwarn org.w3c.dom.svg.SVGSVGElement
-dontwarn org.w3c.dom.traversal.DocumentTraversal
-dontwarn org.w3c.dom.traversal.NodeFilter
-dontwarn org.w3c.dom.traversal.NodeIterator
-dontwarn software.amazon.awssdk.crt.auth.credentials.Credentials
-dontwarn software.amazon.awssdk.crt.auth.credentials.CredentialsProvider

# AWT related warnings suppression - Android doesn't support AWT
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn org.apache.poi.hwmf.**
-dontwarn org.apache.poi.xslf.**
-dontwarn org.apache.pdfbox.**
-dontwarn org.apache.poi.hwmf.record.HwmfDraw
-dontwarn org.apache.poi.hwmf.record.HwmfRegionMode
-dontwarn org.apache.pdfbox.rendering.TilingPaintFactory
-dontwarn org.apache.poi.xslf.draw.SVGUserAgent
-dontwarn org.apache.poi.hwmf.record.HwmfWindowing$WmfCreateRegion
-dontwarn software.amazon.awssdk.crt.auth.credentials.DelegateCredentialsHandler
-dontwarn software.amazon.awssdk.crt.auth.credentials.DelegateCredentialsProvider$DelegateCredentialsProviderBuilder
-dontwarn software.amazon.awssdk.crt.auth.credentials.DelegateCredentialsProvider
-dontwarn software.amazon.awssdk.crt.auth.signing.AwsSigningConfig
-dontwarn software.amazon.awssdk.crt.http.HttpHeader
-dontwarn software.amazon.awssdk.crt.http.HttpMonitoringOptions
-dontwarn software.amazon.awssdk.crt.http.HttpProxyOptions$HttpProxyAuthorizationType
-dontwarn software.amazon.awssdk.crt.http.HttpProxyOptions
-dontwarn software.amazon.awssdk.crt.http.HttpRequest
-dontwarn software.amazon.awssdk.crt.http.HttpRequestBodyStream
-dontwarn software.amazon.awssdk.crt.io.ClientBootstrap
-dontwarn software.amazon.awssdk.crt.io.EventLoopGroup
-dontwarn software.amazon.awssdk.crt.io.ExponentialBackoffRetryOptions
-dontwarn software.amazon.awssdk.crt.io.HostResolver
-dontwarn software.amazon.awssdk.crt.io.StandardRetryOptions
-dontwarn software.amazon.awssdk.crt.io.TlsCipherPreference
-dontwarn software.amazon.awssdk.crt.io.TlsContext
-dontwarn software.amazon.awssdk.crt.io.TlsContextOptions
-dontwarn software.amazon.awssdk.crt.s3.ChecksumAlgorithm
-dontwarn software.amazon.awssdk.crt.s3.ChecksumConfig$ChecksumLocation
-dontwarn software.amazon.awssdk.crt.s3.ChecksumConfig
-dontwarn software.amazon.awssdk.crt.s3.ResumeToken
-dontwarn software.amazon.awssdk.crt.s3.S3Client
-dontwarn software.amazon.awssdk.crt.s3.S3ClientOptions
-dontwarn software.amazon.awssdk.crt.s3.S3MetaRequest
-dontwarn software.amazon.awssdk.crt.s3.S3MetaRequestOptions$MetaRequestType
-dontwarn software.amazon.awssdk.crt.s3.S3MetaRequestOptions
-dontwarn software.amazon.awssdk.crt.s3.S3MetaRequestResponseHandler

# 保持所有Adapter类
-keep class com.example.starlocalrag.*Adapter { *; }
-keep class com.example.starlocalrag.*Adapter$** { *; }

# 保持所有Manager和Handler类
-keep class com.example.starlocalrag.*Manager { *; }
-keep class com.example.starlocalrag.*Handler { *; }
-keep class com.example.starlocalrag.*Manager$** { *; }
-keep class com.example.starlocalrag.*Handler$** { *; }

# ===== 开源项目专用配置 =====
# 开源项目禁止混淆，保持所有类名、方法名、字段名不变
# 只启用压缩和性能优化，便于开源协作、调试和代码追踪

# 保持所有应用类完全不混淆
-keep class com.example.starlocalrag.** {
    *;
}

# 保持所有类名、方法名、字段名不混淆
-keepnames class com.example.starlocalrag.**
-keepclassmembernames class com.example.starlocalrag.** {
    *;
}

# 保持所有内部类和匿名类不混淆
-keepnames class com.example.starlocalrag.**$*
-keepclassmembernames class com.example.starlocalrag.**$* {
    *;
}

# 保持View Binding相关类
-keep class com.example.starlocalrag.databinding.** { *; }

# 保持所有内部类和匿名类
-keep class com.example.starlocalrag.**$* { *; }

# 保持JNI相关
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保持 LlamaCpp JNI 相关类 - 开源项目完全不混淆
-keep class com.starlocalrag.llamacpp.** { *; }
-keepnames class com.starlocalrag.llamacpp.**
-keepclassmembernames class com.starlocalrag.llamacpp.** {
    *;
}

# 保持所有内部类和匿名类不混淆
-keepnames class com.starlocalrag.llamacpp.**$*
-keepclassmembernames class com.starlocalrag.llamacpp.**$* {
    *;
}

# 保持序列化相关
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 保持 JSON 相关类，防止混淆导致运行时错误
-keep class org.json.** { *; }
-keepclassmembers class org.json.** {
    *;
}

# 特别保护 JSONObject 的内部字段和方法
-keep class org.json.JSONObject {
    *;
}
-keepclassmembers class org.json.JSONObject {
    *;
}

# XMLBeans 相关保护规则 - 修复 TypeSystemHolder 字段访问问题
-keep class org.apache.xmlbeans.** { *; }
-keep class org.apache.xmlbeans.metadata.** { *; }
-keep class org.apache.xmlbeans.metadata.system.** { *; }
-keep class org.apache.xmlbeans.metadata.system.sXMLCONFIG.** { *; }
-keep class org.apache.xmlbeans.impl.** { *; }
-keep class org.apache.xmlbeans.impl.schema.** { *; }

# 特别保护 TypeSystemHolder 类及其字段
-keep class org.apache.xmlbeans.metadata.system.sXMLCONFIG.TypeSystemHolder {
    *;
}
-keepclassmembers class org.apache.xmlbeans.metadata.system.sXMLCONFIG.TypeSystemHolder {
    *;
}

# 保护所有 XMLBeans 生成的类
-keep class org.openxmlformats.** { *; }
-keep class org.apache.poi.schemas.** { *; }
-keep class schemaorg_apache_xmlbeans.** { *; }

# 处理缺失的 XMLBeans 相关类警告
-dontwarn org.openxmlformats.schemas.wordprocessingml.**
-dontwarn org.openxmlformats.schemas.spreadsheetml.**
-dontwarn org.openxmlformats.schemas.presentationml.**
-dontwarn org.openxmlformats.schemas.drawingml.**
-dontwarn org.openxmlformats.schemas.officeDocument.**
-dontwarn schemaorg_apache_xmlbeans.**

# 处理 Apache Ant 相关缺失类警告
-dontwarn org.apache.tools.ant.**
-dontwarn org.apache.tools.ant.types.**
-dontwarn org.apache.tools.ant.taskdefs.**

# 处理 Apache Maven 相关缺失类警告
-dontwarn org.apache.maven.**
-dontwarn org.apache.maven.plugin.**
-dontwarn org.apache.maven.plugins.**
-dontwarn org.apache.maven.project.**

# 处理 JavaParser 相关缺失类警告
-dontwarn com.github.javaparser.**

# 处理 Sun 内部 XML 解析器相关缺失类警告
-dontwarn com.sun.org.apache.xml.internal.**
-dontwarn com.sun.xml.**
-dontwarn sun.misc.**

# 保护 XMLBeans 的反射访问
-keepclassmembers class org.apache.xmlbeans.** {
    public static <fields>;
    public static final <fields>;
    public static <methods>;
}

# 保护 XMLBeans 的类型系统
-keep class **.*TypeSystemHolder { *; }
-keepclassmembers class **.*TypeSystemHolder {
    *;
}
