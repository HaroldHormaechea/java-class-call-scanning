package com.hhg.callgraph.scanner.test;

import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.model.TestDescriptor;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Optional;

public class SpockTestDetector implements TestMethodDetector {

    private static final String SPOCK_SPECIFICATION = "spock/lang/Specification";
    private static final String FEATURE_METADATA_DESC = "Lspock/lang/annotation/FeatureMetadata;";

    @Override
    public Optional<TestDescriptor> detect(ClassNode classNode, MethodNode methodNode) {
        if (!SPOCK_SPECIFICATION.equals(classNode.superName)) {
            return Optional.empty();
        }
        String featureName = extractFeatureName(methodNode);
        if (featureName == null) {
            return Optional.empty();
        }
        MethodReference ref = new MethodReference(classNode.name, methodNode.name, methodNode.desc);
        return Optional.of(new TestDescriptor(ref, "Spock", featureName));
    }

    private String extractFeatureName(MethodNode methodNode) {
        String name = findFeatureMetadataName(methodNode.visibleAnnotations);
        if (name != null) return name;
        return findFeatureMetadataName(methodNode.invisibleAnnotations);
    }

    private String findFeatureMetadataName(List<AnnotationNode> annotations) {
        if (annotations == null) return null;
        for (AnnotationNode ann : annotations) {
            if (!FEATURE_METADATA_DESC.equals(ann.desc) || ann.values == null) continue;
            for (int i = 0; i + 1 < ann.values.size(); i += 2) {
                if ("name".equals(ann.values.get(i)) && ann.values.get(i + 1) instanceof String s) {
                    return s;
                }
            }
        }
        return null;
    }
}
