package com.hhg.callgraph.scanner.test;

import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.model.TestDescriptor;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public class JUnit5TestDetector implements TestMethodDetector {

    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Lorg/junit/jupiter/api/Test;",
            "Lorg/junit/jupiter/params/ParameterizedTest;",
            "Lorg/junit/jupiter/api/TestFactory;",
            "Lorg/junit/jupiter/api/RepeatedTest;",
            "Lorg/junit/jupiter/api/TestTemplate;"
    );

    @Override
    public Optional<TestDescriptor> detect(ClassNode classNode, MethodNode methodNode) {
        if (!hasTestAnnotation(methodNode)) {
            return Optional.empty();
        }
        MethodReference ref = new MethodReference(classNode.name, methodNode.name, methodNode.desc);
        return Optional.of(new TestDescriptor(ref, "JUnit5", methodNode.name));
    }

    private boolean hasTestAnnotation(MethodNode methodNode) {
        return containsTestAnnotation(methodNode.visibleAnnotations)
                || containsTestAnnotation(methodNode.invisibleAnnotations);
    }

    private boolean containsTestAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        return annotations.stream().anyMatch(ann -> TEST_ANNOTATIONS.contains(ann.desc));
    }
}
