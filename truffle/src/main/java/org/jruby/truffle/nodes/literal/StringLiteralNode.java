/*
 * Copyright (c) 2013, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.literal;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;
import org.jcodings.Encoding;
import org.jruby.truffle.nodes.RubyNode;
import org.jruby.truffle.nodes.objects.AllocateObjectNode;
import org.jruby.truffle.nodes.objects.AllocateObjectNodeGen;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.util.ByteList;

import java.util.Arrays;

public class StringLiteralNode extends RubyNode {

    private @CompilationFinal byte[] bytes; // deeply immutable
    private final Encoding encoding;
    private final int codeRange;

    @Child private AllocateObjectNode allocateObjectNode;

    public StringLiteralNode(RubyContext context, SourceSection sourceSection, ByteList byteList, int codeRange) {
        super(context, sourceSection);
        assert byteList != null;
        this.bytes = byteList.bytes();
        this.encoding = byteList.getEncoding();
        this.codeRange = codeRange;
        allocateObjectNode = AllocateObjectNodeGen.create(context, sourceSection, false, null, null);
    }

    @Override
    public DynamicObject execute(VirtualFrame frame) {
        final byte[] copy = Arrays.copyOf(bytes, bytes.length);
        final ByteList byteList = new ByteList(copy, encoding, false);
        return allocateObjectNode.allocate(getContext().getCoreLibrary().getStringClass(), byteList, codeRange, null);
    }

}
