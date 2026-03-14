package com.hhg.callgraph.scanner.test;

import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.model.TestDescriptor;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public class SpockTestDetector implements TestMethodDetector {

    // Spock 1.x and 2.x annotation descriptors
    private static final Set<String> FEATURE_METADATA_DESCS = Set.of(
            "Lspock/lang/annotation/FeatureMetadata;",
            "Lorg/spockframework/runtime/model/FeatureMetadata;"
    );

    @Override
    public Optional<TestDescriptor> detect(ClassNode classNode, MethodNode methodNode) {
        String featureName = extractFeatureName(methodNode);
        if (featureName != null) {
            MethodReference ref = new MethodReference(classNode.name, methodNode.name, methodNode.desc);
            return Optional.of(new TestDescriptor(ref, "Spock", featureName));
        }

        // Fallback: Spock feature methods follow the $spock_feature_N_N naming pattern
        if (methodNode.name.startsWith("$spock_feature_")) {
            MethodReference ref = new MethodReference(classNode.name, methodNode.name, methodNode.desc);
            return Optional.of(new TestDescriptor(ref, "Spock", methodNode.name));
        }

        return Optional.empty();
    }

    private String extractFeatureName(MethodNode methodNode) {
        String name = findFeatureMetadataName(methodNode.visibleAnnotations);
        if (name != null) return name;
        return findFeatureMetadataName(methodNode.invisibleAnnotations);
    }

    private String findFeatureMetadataName(List<AnnotationNode> annotations) {
        if (annotations == null) return null;
        for (AnnotationNode ann : annotations) {
            if (!FEATURE_METADATA_DESCS.contains(ann.desc) || ann.values == null) continue;
            for (int i = 0; i + 1 < ann.values.size(); i += 2) {
                if ("name".equals(ann.values.get(i)) && ann.values.get(i + 1) instanceof String s) {
                    return s;
                }
            }
        }
        return null;
    }
}
