package vkr.plugin;

import micans.plugin.spring.MicroserviceHolder;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.ClassType;

import java.util.Set;
import java.util.TreeSet;

/**
 * Outgoing channel ends as sinks that also see taint inside the argument object.
 *
 * <p>A Tai-e sink fires only when the argument variable itself points to a taint object. A Feign
 * call or a RabbitMQ send usually passes a DTO whose String field holds the taint (Micans taints
 * the String fields of web-endpoint DTOs), so the DTO is not a taint object and the channel end is
 * missed. When the analysis finishes, every call in application code to a {@code @FeignClient}
 * interface method or to a {@code convertAndSend}-like method of a Rabbit/AMQP template is checked:
 * each taint object the argument points to, directly or through at most {@link #MAX_DEPTH} levels
 * of instance fields, is printed as
 * {@code ChannelFlow{<source point> -> <caller>[i@Ln] <call>/<arg index>/<field path>}}.
 *
 * <p>Enabled with system property {@code vkr.channelSinks=true}.
 */
public class ChannelArgSink implements Plugin {

    private static final int MAX_DEPTH = 3;
    private static final String FEIGN_CLIENT = "org.springframework.cloud.openfeign.FeignClient";
    private static final Set<String> TEMPLATES = Set.of(
            "org.springframework.amqp.rabbit.core.RabbitTemplate",
            "org.springframework.amqp.rabbit.core.RabbitOperations",
            "org.springframework.amqp.core.AmqpTemplate",
            "org.springframework.amqp.rabbit.core.RabbitMessagingTemplate");
    private static final Set<String> SENDS = Set.of(
            "convertAndSend", "send", "convertSendAndReceive", "convertSendAndReceiveAsType");

    private Solver solver;

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    @Override
    public void onFinish() {
        if (!Boolean.getBoolean("vkr.channelSinks")) {
            return;
        }
        Set<String> lines = new TreeSet<>();
        for (JMethod caller : solver.getCallGraph().reachableMethods().map(m -> m.getMethod()).distinct().toList()) {
            if (caller.isAbstract() || !MicroserviceHolder.isApplication(caller.getDeclaringClass().getName())) {
                continue;
            }
            for (var stmt : caller.getIR()) {
                if (!(stmt instanceof Invoke invoke) || !isChannelCall(invoke)) {
                    continue;
                }
                for (int i = 0; i < invoke.getInvokeExp().getArgCount(); i++) {
                    Var arg = invoke.getInvokeExp().getArg(i);
                    Set<String> found = new TreeSet<>();
                    for (CSVar csArg : solver.getCSManager().getCSVarsOf(arg)) {
                        for (CSObj obj : solver.getPointsToSetOf(csArg)) {
                            collect(obj, "", 0, found, new java.util.HashSet<>());
                        }
                    }
                    for (String f : found) {
                        int sep = f.indexOf('\u0000');
                        lines.add("ChannelFlow{" + f.substring(0, sep) + " -> " + caller + "[" + invoke.getIndex()
                                + "@L" + invoke.getLineNumber() + "] " + invoke.getMethodRef() + "/" + i + "/"
                                + f.substring(sep + 1) + "}");
                    }
                }
            }
        }
        lines.forEach(System.out::println);
        System.out.println("ChannelArgSink: " + lines.size() + " channel flow(s)");
    }

    private boolean isChannelCall(Invoke invoke) {
        if (invoke.isDynamic()) {
            return false;
        }
        var ref = invoke.getMethodRef();
        JClass owner = ref.getDeclaringClass();
        if (owner == null) {
            return false;
        }
        if (owner.isInterface() && owner.hasAnnotation(FEIGN_CLIENT)) {
            return true;
        }
        return TEMPLATES.contains(owner.getName()) && SENDS.contains(ref.getName());
    }

    /** taint objects reachable from obj, each as "source point \0 field path" */
    private void collect(CSObj obj, String path, int depth, Set<String> out, Set<CSObj> seen) {
        if (!seen.add(obj)) {
            return;
        }
        if (ContainerTaint.isTaint(obj)) {
            out.add(obj.getObject().getAllocation() + "\u0000" + path);
            return;
        }
        if (depth >= MAX_DEPTH) {
            return;
        }
        Obj o = obj.getObject();
        if (o.getType() instanceof ArrayType) {
            for (CSObj elem : solver.getPointsToSetOf(solver.getCSManager().getArrayIndex(obj))) {
                collect(elem, path + "[]", depth + 1, out, seen);
            }
            return;
        }
        if (!(o.getType() instanceof ClassType type)) {
            return;
        }
        for (JClass c = type.getJClass(); c != null; c = c.getSuperClass()) {
            for (JField field : c.getDeclaredFields()) {
                if (field.isStatic() || !(field.getType() instanceof ClassType || field.getType() instanceof ArrayType)) {
                    continue;
                }
                var pointer = solver.getCSManager().getInstanceField(obj, field);
                for (CSObj next : solver.getPointsToSetOf(pointer)) {
                    collect(next, path.isEmpty() ? field.getName() : path + "." + field.getName(), depth + 1, out, seen);
                }
            }
        }
    }
}
