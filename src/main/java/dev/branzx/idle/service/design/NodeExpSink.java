package dev.branzx.idle.service.design;

import dev.branzx.idle.node.NodeRecord;

/** Grants exploration EXP to a node, applying level-ups and persistence. */
@FunctionalInterface
public interface NodeExpSink {
    void grant(NodeRecord node, long amount);
}
