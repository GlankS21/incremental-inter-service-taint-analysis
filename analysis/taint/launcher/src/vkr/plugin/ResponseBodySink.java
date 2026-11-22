package vkr.plugin;

import micans.plugin.spring.MicroserviceHolder;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Return;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;

import java.util.Set;
import java.util.TreeSet;

/**
 * The response body of a web handler as a sink (reflected or stored XSS, CWE-79).
 *
 * <p>A Tai-e sink is an argument of a call; the text a {@code @ResponseBody} handler returns is
 * written to the response by Spring's message converters, outside the analysed code. When the
 * analysis finishes, every reachable application method mapped to a request ({@code @RequestMapping}
 * and its variants) whose return value is the body ({@code @ResponseBody} on the method or its class,
 * {@code @RestController}, or a {@code ResponseEntity}/{@code HttpEntity} return type) is checked.
 * The body is the returned String, or the String argument of {@code ResponseEntity.ok(..)},
 * {@code BodyBuilder.body(..)} or {@code new ResponseEntity(..)} in the handler: the entity itself is
 * allocated inside Spring and shared by every handler under 1-call, so its fields are not followed.
 * An object body is serialised as JSON and is not a sink. Each taint object the text body points to
 * is printed as {@code ResponseFlow{<source point> -> <handler>@L<line of the body>}}. A view name
 * returned by a plain {@code @Controller} is not a body: templates escape what they render.
 *
 * <p>Enabled with system property {@code vkr.responseSinks=true} (taint.sinks contains cwe-79).
 */
public class ResponseBodySink implements Plugin {

    private static final String WEB = "org.springframework.web.bind.annotation.";
    private static final Set<String> MAPPINGS = Set.of(
            WEB + "RequestMapping", WEB + "GetMapping", WEB + "PostMapping", WEB + "PutMapping",
            WEB + "PatchMapping", WEB + "DeleteMapping");
    private static final Set<String> ENTITY_TYPES = Set.of(
            "org.springframework.http.ResponseEntity", "org.springframework.http.HttpEntity");
    private static final Set<String> TEXT_TYPES = Set.of("java.lang.String", "java.lang.CharSequence");
    private static final Set<String> BODY_METHODS = Set.of("ok", "body", "<init>");

    private Solver solver;

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    @Override
    public void onFinish() {
        if (!Boolean.getBoolean("vkr.responseSinks")) {
            return;
        }
        Set<String> lines = new TreeSet<>();
        for (JMethod handler : solver.getCallGraph().reachableMethods().map(m -> m.getMethod()).distinct().toList()) {
            if (handler.isAbstract() || !isBodyHandler(handler)) {
                continue;
            }
            boolean text = isText(handler.getReturnType());
            for (Stmt stmt : handler.getIR()) {
                Var body = null;
                if (text && stmt instanceof Return ret) {
                    body = ret.getValue();
                } else if (!text && stmt instanceof Invoke call && isEntityBody(call)) {
                    body = call.getInvokeExp().getArg(0);
                }
                if (body == null || !isText(body.getType())) {
                    continue;
                }
                for (CSVar csVar : solver.getCSManager().getCSVarsOf(body)) {
                    for (CSObj obj : solver.getPointsToSetOf(csVar)) {
                        if (ContainerTaint.isTaint(obj)) {
                            lines.add("ResponseFlow{" + obj.getObject().getAllocation() + " -> " + handler
                                    + "@L" + stmt.getLineNumber() + "}");
                        }
                    }
                }
            }
        }
        lines.forEach(System.out::println);
        System.out.println("ResponseBodySink: " + lines.size() + " response flow(s)");
    }

    static boolean isBodyHandler(JMethod m) {
        JClass owner = m.getDeclaringClass();
        if (!MicroserviceHolder.isApplication(owner.getName()) || MAPPINGS.stream().noneMatch(m::hasAnnotation)) {
            return false;
        }
        if (m.getReturnType() instanceof ClassType t && ENTITY_TYPES.contains(t.getName())) {
            return true;
        }
        return isText(m.getReturnType()) && (m.hasAnnotation(WEB + "ResponseBody")
                || owner.hasAnnotation(WEB + "ResponseBody") || owner.hasAnnotation(WEB + "RestController"));
    }

    /** a body Spring writes as text (StringHttpMessageConverter); an object is serialised as JSON */
    static boolean isText(Type t) {
        return t instanceof ClassType c && TEXT_TYPES.contains(c.getName());
    }

    /** ResponseEntity.ok(body), ResponseEntity.of/status(..).body(body), new ResponseEntity(body, ..) */
    static boolean isEntityBody(Invoke call) {
        if (call.isDynamic()) {  // string concatenation, lambdas: no method ref
            return false;
        }
        var ref = call.getMethodRef();
        String owner = ref.getDeclaringClass().getName();
        return (ENTITY_TYPES.contains(owner) || owner.startsWith("org.springframework.http.ResponseEntity$"))
                && BODY_METHODS.contains(ref.getName()) && !ref.getParameterTypes().isEmpty();
    }
}
