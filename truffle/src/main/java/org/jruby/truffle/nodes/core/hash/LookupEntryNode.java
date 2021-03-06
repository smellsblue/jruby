/*
 * Copyright (c) 2014, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.core.hash;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.utilities.ConditionProfile;
import com.oracle.truffle.api.source.SourceSection;
import org.jruby.truffle.nodes.RubyNode;
import org.jruby.truffle.nodes.core.BasicObjectNodes;
import org.jruby.truffle.nodes.core.BasicObjectNodesFactory;
import org.jruby.truffle.nodes.dispatch.CallDispatchHeadNode;
import org.jruby.truffle.nodes.dispatch.DispatchHeadNodeFactory;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.hash.BucketsStrategy;
import org.jruby.truffle.runtime.hash.Entry;
import org.jruby.truffle.runtime.hash.HashLookupResult;
import org.jruby.truffle.runtime.layouts.Layouts;

public class LookupEntryNode extends RubyNode {

    @Child HashNode hashNode;
    @Child CallDispatchHeadNode eqlNode;
    @Child BasicObjectNodes.ReferenceEqualNode equalNode;
    
    private final ConditionProfile byIdentityProfile = ConditionProfile.createBinaryProfile();

    public LookupEntryNode(RubyContext context, SourceSection sourceSection) {
        super(context, sourceSection);
        hashNode = new HashNode(context, sourceSection);
        eqlNode = DispatchHeadNodeFactory.createMethodCall(context);
        equalNode = BasicObjectNodesFactory.ReferenceEqualNodeFactory.create(context, sourceSection, null, null);
    }

    public HashLookupResult lookup(VirtualFrame frame, DynamicObject hash, Object key) {
        final int hashed = hashNode.hash(frame, key);

        final Entry[] entries = (Entry[]) Layouts.HASH.getStore(hash);
        final int index = BucketsStrategy.getBucketIndex(hashed, entries.length);
        Entry entry = entries[index];

        Entry previousEntry = null;

        while (entry != null) {
            if (byIdentityProfile.profile(Layouts.HASH.getCompareByIdentity(hash))) {
                if (equalNode.executeReferenceEqual(frame, key, entry.getKey())) {
                    return new HashLookupResult(hashed, index, previousEntry, entry);
                }
            } else {
                if (eqlNode.callBoolean(frame, key, "eql?", null, entry.getKey())) {
                    return new HashLookupResult(hashed, index, previousEntry, entry);
                }
            }

            previousEntry = entry;
            entry = entry.getNextInLookup();
        }

        return new HashLookupResult(hashed, index, previousEntry, null);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        throw new UnsupportedOperationException();
    }

}
