/*
 * Copyright (c) 2014, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.runtime;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import org.jruby.truffle.nodes.core.ModuleNodes;
import org.jruby.truffle.runtime.control.RaiseException;
import org.jruby.truffle.runtime.core.RubyClass;
import org.jruby.truffle.runtime.core.RubyModule;
import org.jruby.truffle.runtime.methods.InternalMethod;
import org.jruby.truffle.runtime.util.Function;
import org.jruby.util.IdUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public abstract class ModuleOperations {

    public static boolean includesModule(RubyModule module, RubyModule other) {
        for (RubyModule ancestor : ModuleNodes.getModel(module).ancestors()) {
            if (ancestor == other) {
                return true;
            }
        }

        return false;
    }

    public static boolean assignableTo(RubyClass thisClass, RubyModule otherClass) {
        return includesModule(thisClass, otherClass);
    }

    public static boolean canBindMethodTo(RubyModule origin, RubyModule module) {
        if (!(origin instanceof RubyClass)) {
            return true;
        } else {
            return ((module instanceof RubyClass) && ModuleOperations.assignableTo((RubyClass) module, origin));
        }
    }

    @TruffleBoundary
    public static Map<String, RubyConstant> getAllConstants(RubyModule module) {
        CompilerAsserts.neverPartOfCompilation();

        final Map<String, RubyConstant> constants = new HashMap<>();

        // Look in the current module
        constants.putAll(ModuleNodes.getModel(module).getConstants());

        // Look in ancestors
        for (RubyModule ancestor : ModuleNodes.getModel(module).prependedAndIncludedModules()) {
            for (Map.Entry<String, RubyConstant> constant : ModuleNodes.getModel(ancestor).getConstants().entrySet()) {
                if (!constants.containsKey(constant.getKey())) {
                    constants.put(constant.getKey(), constant.getValue());
                }
            }
        }

        return constants;
    }

    /**
     * @param lexicalScope The surrounding LexicalScope (as in Constant),
     *                     or null if it is ignored (as in Mod::Constant or ::Constant)
     * @param module The receiver of the constant lookup.
     *               Must be identical to lexicalScope.getLiveModule() if lexicalScope != null.
     */
    @TruffleBoundary
    public static RubyConstant lookupConstant(RubyContext context, LexicalScope lexicalScope, RubyModule module, String name) {
        CompilerAsserts.neverPartOfCompilation();
        assert lexicalScope == null || lexicalScope.getLiveModule() == module;

        RubyConstant constant;

        // Look in the current module
        constant = ModuleNodes.getModel(module).getConstants().get(name);

        if (constant != null) {
            return constant;
        }

        // Look in lexical scope
        if (lexicalScope != null) {
            if (lexicalScope != context.getRootLexicalScope()) {
                // Already looked in the top lexical scope, which is module.
                lexicalScope = lexicalScope.getParent();
            }

            while (lexicalScope != context.getRootLexicalScope()) {
                constant = ModuleNodes.getModel(lexicalScope.getLiveModule()).getConstants().get(name);

                if (constant != null) {
                    return constant;
                }

                lexicalScope = lexicalScope.getParent();
            }
        }

        // Look in ancestors
        for (RubyModule ancestor : ModuleNodes.getModel(module).parentAncestors()) {
            constant = ModuleNodes.getModel(ancestor).getConstants().get(name);

            if (constant != null) {
                return constant;
            }
        }

        // Look in Object and its included modules
        if (ModuleNodes.getModel(module).isOnlyAModule()) {
            final RubyClass objectClass = context.getCoreLibrary().getObjectClass();

            constant = ModuleNodes.getModel(objectClass).getConstants().get(name);
            if (constant != null) {
                return constant;
            }

            for (RubyModule ancestor : ModuleNodes.getModel(objectClass).prependedAndIncludedModules()) {
                constant = ModuleNodes.getModel(ancestor).getConstants().get(name);

                if (constant != null) {
                    return constant;
                }
            }
        }

        // Nothing found
        return null;
    }

    public static RubyConstant lookupScopedConstant(RubyContext context, RubyModule module, String fullName, boolean inherit, Node currentNode) {
        CompilerAsserts.neverPartOfCompilation();

        int start = 0, next;
        if (fullName.startsWith("::")) {
            module = context.getCoreLibrary().getObjectClass();
            start += 2;
        }

        while ((next = fullName.indexOf("::", start)) != -1) {
            String segment = fullName.substring(start, next);
            RubyConstant constant = lookupConstantWithInherit(context, module, segment, inherit, currentNode);
            if (constant == null) {
                return null;
            } else if (constant.getValue() instanceof RubyModule) {
                module = (RubyModule) constant.getValue();
            } else {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(context.getCoreLibrary().typeError(fullName.substring(0, next) + " does not refer to class/module", currentNode));
            }
            start = next + 2;
        }

        String lastSegment = fullName.substring(start);
        return lookupConstantWithInherit(context, module, lastSegment, inherit, currentNode);
    }

    public static RubyConstant lookupConstantWithInherit(RubyContext context, RubyModule module, String name, boolean inherit, Node currentNode) {
        if (!IdUtil.isValidConstantName19(name)) {
            CompilerDirectives.transferToInterpreter();
            throw new RaiseException(context.getCoreLibrary().nameError(String.format("wrong constant name %s", name), name, currentNode));
        }

        if (inherit) {
            return ModuleOperations.lookupConstant(context, LexicalScope.NONE, module, name);
        } else {
            return ModuleNodes.getModel(module).getConstants().get(name);
        }
    }

    @TruffleBoundary
    public static Map<String, InternalMethod> getAllMethods(RubyModule module) {
        final Map<String, InternalMethod> methods = new HashMap<>();

        for (RubyModule ancestor : ModuleNodes.getModel(module).ancestors()) {
            for (InternalMethod method : ModuleNodes.getModel(ancestor).getMethods().values()) {
                if (!methods.containsKey(method.getName())) {
                    methods.put(method.getName(), method);
                }
            }
        }

        return methods;
    }

    @TruffleBoundary
    public static Map<String, InternalMethod> getMethodsBeforeLogicalClass(RubyModule module) {
        final Map<String, InternalMethod> methods = new HashMap<>();

        for (RubyModule ancestor : ModuleNodes.getModel(module).ancestors()) {
            // When we find a class which is not a singleton class, we are done
            if (ancestor instanceof RubyClass && !ModuleNodes.getModel(((RubyClass) ancestor)).isSingleton()) {
                break;
            }

            for (InternalMethod method : ModuleNodes.getModel(ancestor).getMethods().values()) {
                if (!methods.containsKey(method.getName())) {
                    methods.put(method.getName(), method);
                }
            }
        }

        return methods;
    }

    @TruffleBoundary
    public static Map<String, InternalMethod> getMethodsUntilLogicalClass(RubyModule module) {
        final Map<String, InternalMethod> methods = new HashMap<>();

        for (RubyModule ancestor : ModuleNodes.getModel(module).ancestors()) {
            for (InternalMethod method : ModuleNodes.getModel(ancestor).getMethods().values()) {
                if (!methods.containsKey(method.getName())) {
                    methods.put(method.getName(), method);
                }
            }

            // When we find a class which is not a singleton class, we are done
            if (ancestor instanceof RubyClass && !ModuleNodes.getModel(((RubyClass) ancestor)).isSingleton()) {
                break;
            }
        }

        return methods;
    }

    @TruffleBoundary
    public static Map<String, InternalMethod> withoutUndefinedMethods(Map<String, InternalMethod> methods) {
        Map<String, InternalMethod> definedMethods = new HashMap<>();
        for (Entry<String, InternalMethod> method : methods.entrySet()) {
            if (!method.getValue().isUndefined()) {
                definedMethods.put(method.getKey(), method.getValue());
            }
        }
        return definedMethods;
    }

    @TruffleBoundary
    public static InternalMethod lookupMethod(RubyModule module, String name) {
        CompilerAsserts.neverPartOfCompilation();

        // Look in ancestors
        for (RubyModule ancestor : ModuleNodes.getModel(module).ancestors()) {
            InternalMethod method = ModuleNodes.getModel(ancestor).getMethods().get(name);

            if (method != null) {
                return method;
            }
        }

        // Nothing found
        return null;
    }

    public static InternalMethod lookupSuperMethod(InternalMethod currentMethod, RubyClass objectMetaClass) {
        String name = currentMethod.getSharedMethodInfo().getName(); // use the original name

        return lookupSuperMethod(currentMethod.getDeclaringModule(), name, objectMetaClass);
    }

    @TruffleBoundary
    public static InternalMethod lookupSuperMethod(RubyModule declaringModule, String name, RubyClass objectMetaClass) {
        CompilerAsserts.neverPartOfCompilation();

        boolean foundDeclaringModule = false;
        for (RubyModule module : ModuleNodes.getModel(objectMetaClass).ancestors()) {
            if (module == declaringModule) {
                foundDeclaringModule = true;
            } else if (foundDeclaringModule) {
                InternalMethod method = ModuleNodes.getModel(module).getMethods().get(name);

                if (method != null) {
                    return method;
                }
            }
        }
        assert foundDeclaringModule : "Did not find the declaring module in "+ ModuleNodes.getModel(objectMetaClass).getName() +" ancestors";

        return null;
    }

    @TruffleBoundary
    public static Map<String, Object> getAllClassVariables(RubyModule module) {
        CompilerAsserts.neverPartOfCompilation();

        final Map<String, Object> classVariables = new HashMap<>();

        classVariableLookup(module, new Function<RubyModule, Object>() {
            @Override
            public Object apply(RubyModule module) {
                classVariables.putAll(ModuleNodes.getModel(module).getClassVariables());
                return null;
            }
        });

        return classVariables;
    }

    @TruffleBoundary
    public static Object lookupClassVariable(RubyModule module, final String name) {
        return classVariableLookup(module, new Function<RubyModule, Object>() {
            @Override
            public Object apply(RubyModule module) {
                return ModuleNodes.getModel(module).getClassVariables().get(name);
            }
        });
    }

    @TruffleBoundary
    public static void setClassVariable(RubyModule module, final String name, final Object value, final Node currentNode) {
        RubyModule found = classVariableLookup(module, new Function<RubyModule, RubyModule>() {
            @Override
            public RubyModule apply(RubyModule module) {
                if (ModuleNodes.getModel(module).getClassVariables().containsKey(name)) {
                    ModuleNodes.getModel(module).setClassVariable(currentNode, name, value);
                    return module;
                } else {
                    return null;
                }
            }
        });

        if (found == null) {
            // Not existing class variable - set in the current module
            ModuleNodes.getModel(module).setClassVariable(currentNode, name, value);
        }
    }

    private static <R> R classVariableLookup(RubyModule module, Function<RubyModule, R> action) {
        CompilerAsserts.neverPartOfCompilation();

        // Look in the current module
        R result = action.apply(module);
        if (result != null) {
            return result;
        }

        // If singleton class, check attached module.
        if (module instanceof RubyClass) {
            RubyClass klass = (RubyClass) module;
            if (ModuleNodes.getModel(klass).isSingleton() && ModuleNodes.getModel(klass).getAttached() != null) {
                module = ModuleNodes.getModel(klass).getAttached();

                result = action.apply(module);
                if (result != null) {
                    return result;
                }
            }
        }

        // Look in ancestors
        for (RubyModule ancestor : ModuleNodes.getModel(module).parentAncestors()) {
            result = action.apply(ancestor);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    public static boolean isMethodPrivateFromName(String name) {
        CompilerAsserts.neverPartOfCompilation();

        return (name.equals("initialize") || name.equals("initialize_copy") ||
                name.equals("initialize_clone") || name.equals("initialize_dup") ||
                name.equals("respond_to_missing?"));
    }

}
