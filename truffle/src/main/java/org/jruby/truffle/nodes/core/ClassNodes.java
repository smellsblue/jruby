/*
 * Copyright (c) 2013, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.core;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;
import org.jruby.runtime.Visibility;
import org.jruby.truffle.nodes.RubyGuards;
import org.jruby.truffle.nodes.RubyNode;
import org.jruby.truffle.nodes.dispatch.CallDispatchHeadNode;
import org.jruby.truffle.nodes.dispatch.DispatchHeadNodeFactory;
import org.jruby.truffle.nodes.objects.Allocator;
import org.jruby.truffle.runtime.NotProvided;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.control.RaiseException;
import org.jruby.truffle.runtime.core.RubyBasicObject;
import org.jruby.truffle.runtime.core.RubyClass;
import org.jruby.truffle.runtime.core.RubyModule;

@CoreClass(name = "Class")
public abstract class ClassNodes {

    /** Special constructor for class Class */
    public static RubyClass createClassClass(RubyContext context, Allocator allocator) {
        return new RubyClass(context, null, null, null, "Class", false, null, allocator);
    }

    /**
     * This constructor supports initialization and solves boot-order problems and should not
     * normally be used from outside this class.
     */
    public static RubyClass createBootClass(RubyClass classClass, RubyClass superclass, String name, Allocator allocator) {
        return new RubyClass(classClass.getContext(), classClass, null, superclass, name, false, null, allocator);
    }

    public static RubyClass createSingletonClassOfObject(RubyContext context, RubyClass superclass, RubyModule attached, String name) {
        // We also need to create the singleton class of a singleton class for proper lookup and consistency.
        // See rb_singleton_class() documentation in MRI.
        // Allocator is null here, we cannot create instances of singleton classes.
        return ModuleNodes.getModel(new RubyClass(context, superclass.getLogicalClass(), null, superclass, name, true, attached, null)).ensureSingletonConsistency();
    }

    @CoreMethod(names = "allocate")
    public abstract static class AllocateNode extends CoreMethodArrayArgumentsNode {

        public AllocateNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public abstract RubyBasicObject executeAllocate(VirtualFrame frame, RubyClass rubyClass);

        @Specialization
        public RubyBasicObject allocate(RubyClass rubyClass) {
            if (ModuleNodes.getModel(rubyClass).isSingleton()) {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(getContext().getCoreLibrary().typeError("can't create instance of singleton class", this));
            }
            return rubyClass.allocate(this);
        }

    }

    @CoreMethod(names = "new", needsBlock = true, argumentsAsArray = true)
    public abstract static class NewNode extends CoreMethodArrayArgumentsNode {

        @Child private CallDispatchHeadNode allocateNode;
        @Child private CallDispatchHeadNode initialize;

        public NewNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            allocateNode = DispatchHeadNodeFactory.createMethodCallOnSelf(context);
            initialize = DispatchHeadNodeFactory.createMethodCallOnSelf(context);
        }

        @Specialization
        public Object newInstance(VirtualFrame frame, RubyClass rubyClass, Object[] args, NotProvided block) {
            return doNewInstance(frame, rubyClass, args, null);
        }

        @Specialization(guards = "isRubyProc(block)")
        public Object newInstance(VirtualFrame frame, RubyClass rubyClass, Object[] args, RubyBasicObject block) {
            return doNewInstance(frame, rubyClass, args, block);
        }

        private Object doNewInstance(VirtualFrame frame, RubyClass rubyClass, Object[] args, RubyBasicObject block) {
            assert block == null || RubyGuards.isRubyProc(block);

            final Object instance = allocateNode.call(frame, rubyClass, "allocate", null);
            initialize.call(frame, instance, "initialize", block, args);
            return instance;
        }
    }

    @CoreMethod(names = "initialize", optional = 1, needsBlock = true)
    public abstract static class InitializeNode extends CoreMethodArrayArgumentsNode {

        @Child private ModuleNodes.InitializeNode moduleInitializeNode;
        @Child private CallDispatchHeadNode inheritedNode;

        public InitializeNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        void triggerInheritedHook(VirtualFrame frame, RubyClass subClass, RubyClass superClass) {
            if (inheritedNode == null) {
                CompilerDirectives.transferToInterpreter();
                inheritedNode = insert(DispatchHeadNodeFactory.createMethodCallOnSelf(getContext()));
            }
            inheritedNode.call(frame, superClass, "inherited", null, subClass);
        }

        void moduleInitialize(VirtualFrame frame, RubyClass rubyClass, RubyBasicObject block) {
            assert RubyGuards.isRubyProc(block);

            if (moduleInitializeNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                moduleInitializeNode = insert(ModuleNodesFactory.InitializeNodeFactory.create(getContext(), getSourceSection(), new RubyNode[]{null,null}));
            }
            moduleInitializeNode.executeInitialize(frame, rubyClass, block);
        }

        @Specialization
        public RubyClass initialize(VirtualFrame frame, RubyClass rubyClass, NotProvided superclass, NotProvided block) {
            return initializeGeneralWithoutBlock(frame, rubyClass, getContext().getCoreLibrary().getObjectClass());
        }

        @Specialization
        public RubyClass initialize(VirtualFrame frame, RubyClass rubyClass, RubyClass superclass, NotProvided block) {
            return initializeGeneralWithoutBlock(frame, rubyClass, superclass);
        }

        @Specialization(guards = "isRubyProc(block)")
        public RubyClass initialize(VirtualFrame frame, RubyClass rubyClass, NotProvided superclass, RubyBasicObject block) {
            return initializeGeneralWithBlock(frame, rubyClass, getContext().getCoreLibrary().getObjectClass(), block);
        }

        @Specialization(guards = "isRubyProc(block)")
        public RubyClass initialize(VirtualFrame frame, RubyClass rubyClass, RubyClass superclass, RubyBasicObject block) {
            return initializeGeneralWithBlock(frame, rubyClass, superclass, block);
        }

        private RubyClass initializeGeneralWithoutBlock(VirtualFrame frame, RubyClass rubyClass, RubyClass superclass) {
            ModuleNodes.getModel(rubyClass).initialize(superclass);
            triggerInheritedHook(frame, rubyClass, superclass);

            return rubyClass;
        }

        private RubyClass initializeGeneralWithBlock(VirtualFrame frame, RubyClass rubyClass, RubyClass superclass, RubyBasicObject block) {
            assert RubyGuards.isRubyProc(block);

            ModuleNodes.getModel(rubyClass).initialize(superclass);
            triggerInheritedHook(frame, rubyClass, superclass);
            moduleInitialize(frame, rubyClass, block);

            return rubyClass;
        }

    }

    @CoreMethod(names = "inherited", required = 1, visibility = Visibility.PRIVATE)
    public abstract static class InheritedNode extends CoreMethodArrayArgumentsNode {

        public InheritedNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public RubyBasicObject inherited(Object subclass) {
            return nil();
        }

    }

    @CoreMethod(names = "superclass")
    public abstract static class SuperClassNode extends CoreMethodArrayArgumentsNode {

        public SuperClassNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public Object getSuperClass(RubyClass rubyClass) {
            RubyClass superclass = ModuleNodes.getModel(rubyClass).getSuperClass();
            if (superclass == null) {
                return nil();
            } else {
                return superclass;
            }
        }
    }

    public static class ClassAllocator implements Allocator {

        @Override
        public RubyBasicObject allocate(RubyContext context, RubyClass rubyClass, Node currentNode) {
            return new RubyClass(context, context.getCoreLibrary().getClassClass(), null, null, null, false, null, null);
        }

    }
}
