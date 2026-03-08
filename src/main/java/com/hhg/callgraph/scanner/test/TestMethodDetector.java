package com.hhg.callgraph.scanner.test;

import com.hhg.callgraph.model.TestDescriptor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Optional;

public interface TestMethodDetector {

    /**
     * Inspects a class/method pair and returns a {@link TestDescriptor} if the method is a test,
     * or empty if this detector does not recognise it as one.
     */
    Optional<TestDescriptor> detect(ClassNode classNode, MethodNode methodNode);
}
