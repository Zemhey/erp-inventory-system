package com.erp.support;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * 在内存里编译一段 Java 源码，并交给 ArchUnit 分析（测试"规则自检"用）。
 *
 * <p><b>为什么需要它</b>：架构规则最大的风险是"看起来在保护你，其实什么也没拦"
 * （假绿）。要证明一条规则有效，最直接的办法就是<b>喂给它一段违规代码，
 * 看它是否报错</b>。把违规样本放进 {@code src/main} 会让构建失败，
 * 放进 {@code src/test} 又会被 {@code DoNotIncludeTests} 排除 ——
 * 所以在内存里现场编译是唯一干净的做法：不落盘、不污染代码库。
 */
public final class ProbeClassCompiler {

    private ProbeClassCompiler() {
    }

    /**
     * 编译源码并导入为 ArchUnit 类图。
     *
     * @param className 全限定类名（须与源码里的 package/class 一致）
     * @param source    Java 源码
     */
    public static JavaClasses compile(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("当前 JVM 没有 JavaCompiler（需要 JDK 而不是 JRE）");
        }
        Map<String, ByteArrayOutputStream> outputs = new HashMap<>();
        try (StandardJavaFileManager std = compiler.getStandardFileManager(
                null, null, StandardCharsets.UTF_8)) {
            JavaFileManager fm = new ForwardingJavaFileManager<StandardJavaFileManager>(std) {
                @Override
                public JavaFileObject getJavaFileForOutput(Location location, String name,
                                                           JavaFileObject.Kind kind,
                                                           FileObject sibling) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    outputs.put(name, buffer);
                    return new SimpleJavaFileObject(
                            URI.create("bytes:///" + name.replace('.', '/') + kind.extension), kind) {
                        @Override
                        public OutputStream openOutputStream() {
                            return buffer;
                        }
                    };
                }
            };
            Boolean ok = compiler.getTask(null, fm, null,
                    List.of("-classpath", System.getProperty("java.class.path")),
                    null, List.of(new SourceObject(className, source))).call();
            if (!Boolean.TRUE.equals(ok)) {
                throw new IllegalStateException("违规探针源码编译失败：" + className);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("关闭文件管理器失败", e);
        }

        byte[] bytes = outputs.containsKey(className)
                ? outputs.get(className).toByteArray()
                : null;
        if (bytes == null) {
            throw new IllegalStateException("编译产物中找不到 " + className + "，实际产物：" + outputs.keySet());
        }
        // ArchUnit 1.3 的 ClassFileImporter 提供 importPath(String|Path)，
        // 没有"直接导入字节数组"的入口。因此把编译产物写到临时目录再导入。
        // 用 JUnit 的 @TempDir 语义手写（保持本工具类不依赖 JUnit 生命周期）：
        // 每次调用建一个独立子目录，测试进程退出时由系统临时目录清理。
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("archunit-probe-");
            java.nio.file.Path classFile = dir.resolve(className.replace('.', '/') + ".class");
            java.nio.file.Files.createDirectories(classFile.getParent());
            java.nio.file.Files.write(classFile, bytes);
            JavaClasses imported = new ClassFileImporter().importPath(dir);
            if (imported.isEmpty()) {
                throw new IllegalStateException("探针类导入失败：" + className);
            }
            return imported;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("写入探针 class 文件失败", e);
        }
    }

    /** 内存里的源码对象。 */
    private static final class SourceObject extends SimpleJavaFileObject {
        private final String code;

        SourceObject(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
