package com.hhg.callgraph.scanner;

import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.FieldAccessIndex;
import com.hhg.callgraph.model.FieldReference;
import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.model.SourceIndex;
import com.hhg.callgraph.model.SourceLocation;
import com.hhg.callgraph.model.TestIndex;
import com.hhg.callgraph.scanner.test.TestMethodDetector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ClassFileScanner {

    private final List<TestMethodDetector> detectors;

    public ClassFileScanner() {
        this(List.of());
    }

    public ClassFileScanner(List<TestMethodDetector> detectors) {
        this.detectors = List.copyOf(detectors);
    }

    /**
     * Main entry point — accepts a directory, JAR, WAR, or EAR.
     */
    public ScanResult scanPath(Path dirOrArchive) throws IOException {
        if (Files.isDirectory(dirOrArchive)) {
            return scan(dirOrArchive);
        }
        String name = dirOrArchive.getFileName().toString().toLowerCase();
        if (name.endsWith(".war") || name.endsWith(".ear")) {
            return doScanWar(dirOrArchive);
        }
        if (name.endsWith(".jar")) {
            return doScanJar(dirOrArchive);
        }
        throw new IllegalArgumentException(
                "Path is not a directory or a recognised archive (.jar/.war/.ear): " + dirOrArchive);
    }

    public ScanResult scan(Path classesRootDir) throws IOException {
        CallGraph graph = new CallGraph();
        SourceIndex sourceIndex = new SourceIndex();
        FieldAccessIndex fieldAccessIndex = new FieldAccessIndex();
        TestIndex testIndex = new TestIndex();

        try (Stream<Path> paths = Files.walk(classesRootDir)) {
            paths.filter(p -> p.toString().endsWith(".class"))
                    .forEach(classFile -> processClassFile(graph, sourceIndex, fieldAccessIndex, testIndex, classFile));
        }

        return new ScanResult(graph, sourceIndex, fieldAccessIndex, testIndex);
    }

    // --- Archive scanning methods (static wrappers kept for backward compatibility) ---

    public static ScanResult scanWar(Path warPath) throws IOException {
        return new ClassFileScanner().doScanWar(warPath);
    }

    public static ScanResult scanJar(Path jarPath) throws IOException {
        return new ClassFileScanner().doScanJar(jarPath);
    }

    private ScanResult doScanWar(Path warPath) throws IOException {
        CallGraph graph = new CallGraph();
        SourceIndex sourceIndex = new SourceIndex();
        FieldAccessIndex fieldAccessIndex = new FieldAccessIndex();
        TestIndex testIndex = new TestIndex();

        try (FileSystem fs = FileSystems.newFileSystem(warPath, (ClassLoader) null)) {
            Path classesRoot = fs.getPath("WEB-INF/classes");
            if (Files.isDirectory(classesRoot)) {
                try (Stream<Path> paths = Files.walk(classesRoot)) {
                    paths.filter(p -> p.toString().endsWith(".class"))
                            .forEach(classFile -> {
                                try {
                                    byte[] bytes = Files.readAllBytes(classFile);
                                    processClassBytes(bytes, graph, sourceIndex, fieldAccessIndex, testIndex, detectors);
                                } catch (IOException e) {
                                    throw new RuntimeException("Failed to read class in WAR: " + classFile, e);
                                }
                            });
                }
            }

            Path libDir = fs.getPath("WEB-INF/lib");
            if (Files.isDirectory(libDir)) {
                try (Stream<Path> paths = Files.list(libDir)) {
                    paths.filter(p -> p.toString().endsWith(".jar"))
                            .forEach(jarFile -> {
                                try {
                                    byte[] jarBytes = Files.readAllBytes(jarFile);
                                    scanNestedJar(jarBytes, graph, sourceIndex, fieldAccessIndex, testIndex, detectors);
                                } catch (IOException e) {
                                    throw new RuntimeException("Failed to read nested JAR in WAR: " + jarFile, e);
                                }
                            });
                }
            }
        }

        return new ScanResult(graph, sourceIndex, fieldAccessIndex, testIndex);
    }

    private ScanResult doScanJar(Path jarPath) throws IOException {
        CallGraph graph = new CallGraph();
        SourceIndex sourceIndex = new SourceIndex();
        FieldAccessIndex fieldAccessIndex = new FieldAccessIndex();
        TestIndex testIndex = new TestIndex();

        try (FileSystem fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
            Path root = fs.getPath("/");
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(p -> p.toString().endsWith(".class"))
                        .forEach(classFile -> {
                            try {
                                byte[] bytes = Files.readAllBytes(classFile);
                                processClassBytes(bytes, graph, sourceIndex, fieldAccessIndex, testIndex, detectors);
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to read class in JAR: " + classFile, e);
                            }
                        });
            }
        }

        return new ScanResult(graph, sourceIndex, fieldAccessIndex, testIndex);
    }

    private static void scanNestedJar(byte[] jarBytes, CallGraph graph, SourceIndex sourceIndex,
                                      FieldAccessIndex fieldAccessIndex, TestIndex testIndex,
                                      List<TestMethodDetector> detectors) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    processClassBytes(zis.readAllBytes(), graph, sourceIndex, fieldAccessIndex, testIndex, detectors);
                }
                zis.closeEntry();
            }
        }
    }

    // --- Core processing ---

    private void processClassFile(CallGraph graph, SourceIndex sourceIndex,
                                  FieldAccessIndex fieldAccessIndex, TestIndex testIndex, Path classFile) {
        try {
            byte[] bytecode = Files.readAllBytes(classFile);
            processClassBytes(bytecode, graph, sourceIndex, fieldAccessIndex, testIndex, detectors);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read class file: " + classFile, e);
        }
    }

    private static void processClassBytes(byte[] bytecode, CallGraph graph, SourceIndex sourceIndex,
                                          FieldAccessIndex fieldAccessIndex, TestIndex testIndex,
                                          List<TestMethodDetector> detectors) {
        ClassReader classReader = new ClassReader(bytecode);

        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classReader.accept(classNode, 0);

        String sourceFile = classNode.sourceFile;
        if (sourceFile == null) {
            String simpleName = classNode.name;
            int lastSlash = simpleName.lastIndexOf('/');
            if (lastSlash >= 0) simpleName = simpleName.substring(lastSlash + 1);
            int dollarIdx = simpleName.indexOf('$');
            if (dollarIdx >= 0) simpleName = simpleName.substring(0, dollarIdx);
            sourceFile = simpleName + ".java";
        }

        for (MethodNode methodNode : classNode.methods) {
            processMethod(graph, sourceIndex, fieldAccessIndex, testIndex, detectors, classNode, methodNode, sourceFile);
        }
    }

    private static void processMethod(CallGraph graph, SourceIndex sourceIndex, FieldAccessIndex fieldAccessIndex,
                                      TestIndex testIndex, List<TestMethodDetector> detectors,
                                      ClassNode classNode, MethodNode methodNode, String sourceFile) {
        MethodReference caller = new MethodReference(classNode.name, methodNode.name, methodNode.desc);

        int startLine = Integer.MAX_VALUE;
        int endLine = Integer.MIN_VALUE;

        for (AbstractInsnNode instruction : methodNode.instructions) {
            if (instruction instanceof LineNumberNode ln && ln.line > 0) {
                if (ln.line < startLine) startLine = ln.line;
                if (ln.line > endLine) endLine = ln.line;
            }
            if (instruction instanceof MethodInsnNode methodInsn) {
                MethodReference callee = new MethodReference(methodInsn.owner, methodInsn.name, methodInsn.desc);
                graph.addCall(caller, callee);
            }
            if (instruction instanceof FieldInsnNode fieldInsn) {
                FieldReference field = new FieldReference(fieldInsn.owner, fieldInsn.name, fieldInsn.desc);
                boolean isWrite = fieldInsn.getOpcode() == Opcodes.PUTFIELD
                               || fieldInsn.getOpcode() == Opcodes.PUTSTATIC;
                if (isWrite) fieldAccessIndex.addWrite(caller, field);
                else         fieldAccessIndex.addRead(caller, field);
            }
        }

        boolean hasLineInfo = startLine != Integer.MAX_VALUE;
        SourceLocation loc = hasLineInfo
                ? new SourceLocation(sourceFile, startLine, endLine)
                : new SourceLocation(sourceFile, -1, -1);
        sourceIndex.add(caller, loc);

        for (TestMethodDetector detector : detectors) {
            detector.detect(classNode, methodNode).ifPresent(testIndex::add);
        }
    }
}
