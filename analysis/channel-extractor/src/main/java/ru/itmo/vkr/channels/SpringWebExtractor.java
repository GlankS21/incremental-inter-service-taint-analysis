package ru.itmo.vkr.channels;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import ru.itmo.vkr.channels.model.CodeRef;
import ru.itmo.vkr.channels.model.Endpoint;
import ru.itmo.vkr.channels.model.HttpClientMethod;
import ru.itmo.vkr.channels.model.Param;
import ru.itmo.vkr.channels.model.Unresolved;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring MVC handler methods, OpenFeign client methods and {@code RestTemplate} requests of one
 * service. A {@code RestTemplate} request becomes a client when its URL resolves to a service host
 * and a path; otherwise, like every {@code RestClient} and {@code WebClient} call, it is listed as
 * unresolved.
 */
final class SpringWebExtractor {

    record Result(List<Endpoint> endpoints, List<HttpClientMethod> clients, List<Unresolved> unresolved) {
    }

    private record Mapping(List<String> httpMethods, List<String> paths) {
    }

    private static final Map<String, String> SHORTCUT_MAPPINGS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
    private static final Map<String, String> PARAM_SOURCES = Map.of(
            "PathVariable", "PATH", "RequestParam", "QUERY", "RequestBody", "BODY", "RequestHeader", "HEADER",
            "CookieValue", "COOKIE", "ModelAttribute", "QUERY", "RequestPart", "BODY",
            "AuthenticationPrincipal", "PRINCIPAL");
    private static final Set<String> FRAMEWORK_TYPES = Set.of(
            "Authentication", "Principal", "Jwt", "JwtAuthenticationToken", "HttpServletRequest",
            "HttpServletResponse", "ServerHttpRequest", "ServerHttpResponse", "ServerWebExchange", "HttpSession",
            "Locale", "BindingResult", "Model", "UriComponentsBuilder");
    private static final Set<String> QUERY_TYPES = Set.of("Pageable", "Sort");
    private static final Set<String> HTTP_CLIENT_TYPES = Set.of("RestClient", "WebClient");
    private static final Set<String> REST_TEMPLATE_TYPES = Set.of("RestTemplate", "RestOperations");
    private static final Map<String, String> REST_TEMPLATE_METHODS = Map.of(
            "getForObject", "GET", "getForEntity", "GET", "postForObject", "POST", "postForEntity", "POST",
            "postForLocation", "POST", "put", "PUT", "delete", "DELETE", "patchForObject", "PATCH",
            "headForHeaders", "HEAD", "optionsForAllow", "OPTIONS");
    /** RestTemplate methods taking the {@code HttpMethod} as second argument. */
    private static final Set<String> REST_TEMPLATE_METHOD_ARGUMENT = Set.of("exchange", "execute");
    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE");
    private static final String UNRESOLVED_SEGMENT = "{unresolved}";

    private final Path root;
    private final SourceIndex index;
    private final ValueResolver resolver;
    private final GatewayRoutes gateway;
    /** Host names a service is reached by: its application name and its module directory name. */
    private final Map<String, String> serviceAliases = new HashMap<>();

    SpringWebExtractor(Path root, SourceIndex index, ValueResolver resolver, GatewayRoutes gateway) {
        this.root = root;
        this.index = index;
        this.resolver = resolver;
        this.gateway = gateway;
        for (SourceIndex.Module service : index.services()) {
            serviceAliases.putIfAbsent(service.dir().getFileName().toString(), service.name());
        }
        for (SourceIndex.Module service : index.services()) {
            serviceAliases.put(service.name(), service.name());
        }
    }

    Result extract(SourceIndex.Module module) {
        List<Endpoint> endpoints = new ArrayList<>();
        List<HttpClientMethod> clients = new ArrayList<>();
        List<Unresolved> unresolved = new ArrayList<>();
        for (CompilationUnit unit : module.units()) {
            for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                if (Ast.hasAnnotation(type, "RestController") || Ast.hasAnnotation(type, "Controller")) {
                    endpoints(module, type, endpoints);
                }
                if (type.isInterface()) {
                    Ast.annotation(type, "FeignClient")
                            .ifPresent(feign -> feignClient(module, type, feign, clients, false));
                }
            }
            programmaticHttpCalls(module, unit, clients, unresolved);
        }
        // clients declared in a shared API module belong to the services that call them
        for (SourceIndex.Module library : index.modules()) {
            if (library.service()) {
                continue;
            }
            for (CompilationUnit unit : library.units()) {
                for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                    if (type.isInterface()) {
                        Ast.annotation(type, "FeignClient")
                                .ifPresent(feign -> feignClient(module, type, feign, clients, true));
                    }
                }
            }
        }
        return new Result(endpoints, clients, unresolved);
    }

    private void endpoints(SourceIndex.Module module, ClassOrInterfaceDeclaration controller, List<Endpoint> out) {
        List<String> prefixes = mapping(controller).map(Mapping::paths).orElse(List.of(""));
        for (MethodDeclaration handler : controller.getMethods()) {
            Optional<Mapping> mapping = mapping(handler);
            if (mapping.isEmpty()) {
                continue;
            }
            List<Param> params = params(handler);
            for (String prefix : prefixes) {
                for (String path : mapping.get().paths()) {
                    String fullPath = Patterns.joinPaths(prefix, path);
                    for (String httpMethod : mapping.get().httpMethods()) {
                        out.add(new Endpoint("ep:" + module.name() + ":" + httpMethod + " " + fullPath,
                                module.name(), httpMethod, fullPath, params,
                                gateway.exposes(module.name(), fullPath), Ast.ref(handler, root)));
                    }
                }
            }
        }
    }

    private void feignClient(SourceIndex.Module module, ClassOrInterfaceDeclaration client, AnnotationExpr feign,
                             List<HttpClientMethod> out, boolean calledOnly) {
        String target = first(Ast.attribute(feign, "name", "value", "serviceId"));
        String url = first(Ast.attribute(feign, "url"));
        String basePath = first(Ast.attribute(feign, "path"));
        List<String> prefixes = mapping(client).map(Mapping::paths).orElse(List.of(""));
        for (MethodDeclaration method : client.getMethods()) {
            Optional<Mapping> mapping = mapping(method);
            if (mapping.isEmpty()) {
                continue;
            }
            List<Param> params = params(method);
            List<CodeRef> callSites = callSites(module, client.getNameAsString(), method.getNameAsString());
            if (calledOnly && callSites.isEmpty()) {
                continue;
            }
            for (String prefix : prefixes) {
                for (String path : mapping.get().paths()) {
                    String fullPath = Patterns.joinPaths(basePath, prefix, path);
                    for (String httpMethod : mapping.get().httpMethods()) {
                        String id = "http:" + module.name() + ":" + client.getNameAsString() + "#"
                                + method.getNameAsString() + ":" + httpMethod + " " + fullPath;
                        out.add(new HttpClientMethod(id, module.name(), target, url, httpMethod, fullPath, params,
                                Ast.ref(method, root), callSites, HttpClientMethod.FEIGN));
                    }
                }
            }
        }
    }

    /**
     * Calls of a client method through a field, parameter or local variable of the client type.
     */
    private List<CodeRef> callSites(SourceIndex.Module module, String clientType, String methodName) {
        List<CodeRef> sites = new ArrayList<>();
        for (CompilationUnit unit : module.units()) {
            for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals(methodName)
                        && resolver.receiverType(call).filter(clientType::equals).isPresent()) {
                    sites.add(Ast.ref(call, root));
                }
            }
        }
        return sites;
    }

    private void programmaticHttpCalls(SourceIndex.Module module, CompilationUnit unit, List<HttpClientMethod> clients,
                                       List<Unresolved> out) {
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if (call.getScope().flatMap(this::clientType).filter(REST_TEMPLATE_TYPES::contains).isPresent()) {
                restTemplateCall(module, call, clients, out);
                continue;
            }
            boolean outermostOfChain = call.getParentNode()
                    .filter(parent -> parent instanceof MethodCallExpr outer
                            && outer.getScope().filter(scope -> scope == call).isPresent())
                    .isEmpty();
            if (!outermostOfChain) {
                continue;
            }
            Expression receiver = call;
            while (receiver instanceof MethodCallExpr chained && chained.getScope().isPresent()) {
                receiver = chained.getScope().get();
            }
            clientType(receiver).filter(HTTP_CLIENT_TYPES::contains).ifPresent(client -> out.add(new Unresolved(
                    module.name(), "HTTP_CALL", client + " call: target URL is built at runtime and not modelled",
                    Ast.ref(call, root))));
        }
    }

    private Optional<String> clientType(Expression receiver) {
        if (receiver instanceof NameExpr name) {
            return resolver.declaredType(name);
        }
        if (receiver instanceof FieldAccessExpr field && field.getScope().isThisExpr()) {
            return resolver.fieldType(Ast.enclosingType(field), field.getNameAsString());
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ RestTemplate

    /**
     * A request sent with {@code RestTemplate}: the URL (first argument) is evaluated to a template
     * whose runtime parts are holes, and {@code http://<service>[:port]/<path>} gives the target
     * service and path. The HTTP method comes from the method name or from the {@code HttpMethod}
     * argument of {@code exchange}/{@code execute}. A call inside a private helper is resolved once
     * per caller, with the values that caller passes, like publishers.
     */
    private void restTemplateCall(SourceIndex.Module module, MethodCallExpr call, List<HttpClientMethod> clients,
                                  List<Unresolved> out) {
        String name = call.getNameAsString();
        boolean methodArgument = REST_TEMPLATE_METHOD_ARGUMENT.contains(name);
        if (!methodArgument && !REST_TEMPLATE_METHODS.containsKey(name)) {
            return; // configuration (getInterceptors, setErrorHandler...), not a request
        }
        CodeRef site = Ast.ref(call, root);
        // exchange(url, method, entity, type, ...) / execute(url, method, callback, extractor, ...) vs
        // exchange(RequestEntity, type)
        if (call.getArguments().isEmpty() || (methodArgument && call.getArguments().size() < 3)) {
            addOnce(out, new Unresolved(module.name(), "HTTP_CALL", "RestTemplate." + name
                    + "(RequestEntity): URL and method are carried by a RequestEntity, not modelled", site));
            return;
        }
        Expression url = uriArgument(call.getArgument(0));
        for (ValueResolver.Ctx ctx : resolver.contextsFor(call)) {
            MethodCallExpr originCall = ctx.originSite();
            CodeRef origin = originCall == null ? site : Ast.ref(originCall, root);
            String httpMethod = methodArgument ? httpMethod(call.getArgument(1), ctx) : REST_TEMPLATE_METHODS.get(name);
            if (httpMethod == null) {
                addOnce(out, new Unresolved(module.name(), "HTTP_CALL", "RestTemplate." + name
                        + ": HTTP method not statically known in " + call.getArgument(1), origin));
                continue;
            }
            for (String template : resolver.templates(url, ctx)) {
                UrlTarget target = UrlTarget.parse(template);
                if (target.problem() != null) {
                    addOnce(out, new Unresolved(module.name(), "HTTP_CALL", "RestTemplate." + name + ": "
                            + target.problem() + " in URL " + UrlTarget.display(template), origin));
                    continue;
                }
                String service = serviceAliases.getOrDefault(target.host(), target.host());
                String id = "http:" + module.name() + ":" + origin.label() + ":" + httpMethod + " " + service
                        + target.path();
                if (clients.stream().noneMatch(c -> c.id().equals(id))) {
                    clients.add(new HttpClientMethod(id, module.name(), service, null, httpMethod, target.path(),
                            List.of(), site, List.of(origin), HttpClientMethod.REST_TEMPLATE));
                }
            }
        }
    }

    private static void addOnce(List<Unresolved> out, Unresolved unresolved) {
        if (!out.contains(unresolved)) {
            out.add(unresolved);
        }
    }

    /**
     * {@code URI.create(s)} and {@code new URI(s)} carry the URL string {@code s}.
     */
    private static Expression uriArgument(Expression argument) {
        if (argument instanceof MethodCallExpr call && call.getNameAsString().equals("create")
                && call.getArguments().size() == 1
                && call.getScope().filter(s -> Ast.simpleName(s.toString()).equals("URI")).isPresent()) {
            return call.getArgument(0);
        }
        if (argument instanceof ObjectCreationExpr creation && creation.getType().getNameAsString().equals("URI")
                && creation.getArguments().size() == 1) {
            return creation.getArgument(0);
        }
        return argument;
    }

    /**
     * {@code HttpMethod.POST}, {@code HttpMethod.valueOf("POST")} or a statically imported
     * {@code POST}; {@code null} when not statically known.
     */
    private String httpMethod(Expression expr, ValueResolver.Ctx ctx) {
        if (expr instanceof FieldAccessExpr field && Ast.simpleName(field.getScope().toString()).equals("HttpMethod")) {
            return field.getNameAsString();
        }
        if (expr instanceof MethodCallExpr call && call.getNameAsString().equals("valueOf")
                && call.getArguments().size() == 1
                && call.getScope().filter(s -> Ast.simpleName(s.toString()).equals("HttpMethod")).isPresent()) {
            Set<String> values = resolver.strings(call.getArgument(0), ctx);
            return values.size() == 1 ? values.iterator().next().toUpperCase(Locale.ROOT) : null;
        }
        if (expr instanceof NameExpr name && HTTP_METHODS.contains(name.getNameAsString())) {
            return name.getNameAsString();
        }
        return null;
    }

    /**
     * Target of a URL template: the {@code host} and the normalised {@code path}, in which every
     * segment holding a runtime part is {@code {?}}; or the {@code problem} that prevents resolving it.
     */
    record UrlTarget(String host, String path, String problem) {

        private static final Pattern URL = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/]*)(.*)$", Pattern.DOTALL);
        private static final String HOLE = String.valueOf(ValueResolver.HOLE);
        static final String RUNTIME_SEGMENT = "{?}";

        static UrlTarget parse(String template) {
            String url = template;
            for (char cut : new char[]{'?', '#'}) {
                int at = url.indexOf(cut);
                if (at >= 0) {
                    url = url.substring(0, at);
                }
            }
            Matcher matcher = URL.matcher(url);
            if (!matcher.matches()) {
                return problem(url.startsWith(HOLE) ? "base URL not statically known" : "not an absolute http URL");
            }
            String host = matcher.group(1);
            host = host.substring(host.indexOf('@') + 1);
            int port = host.indexOf(':');
            if (port >= 0) {
                host = host.substring(0, port);
            }
            String rawPath = matcher.group(2);
            if (rawPath.isEmpty() && host.endsWith(HOLE) && host.indexOf(HOLE) == host.length() - 1
                    && host.length() > 1) {
                // "http://svc" + path
                return problem("request path not statically known");
            }
            if (host.isEmpty() || host.contains(HOLE) || host.contains("{")) {
                return problem("target host not statically known");
            }
            StringBuilder path = new StringBuilder();
            for (String segment : rawPath.split("/")) {
                if (!segment.isEmpty()) {
                    path.append('/').append(segment.contains(HOLE) ? RUNTIME_SEGMENT : segment);
                }
            }
            return new UrlTarget(host, Patterns.joinPaths(path.toString()), null);
        }

        static String display(String template) {
            return template.replace(HOLE, RUNTIME_SEGMENT);
        }

        private static UrlTarget problem(String problem) {
            return new UrlTarget(null, null, problem);
        }
    }

    private Optional<Mapping> mapping(NodeWithAnnotations<?> node) {
        for (AnnotationExpr annotation : node.getAnnotations()) {
            String name = Ast.simpleName(annotation.getNameAsString());
            String shortcut = SHORTCUT_MAPPINGS.get(name);
            if (shortcut != null) {
                return Optional.of(new Mapping(List.of(shortcut), paths(annotation)));
            }
            if (name.equals("RequestMapping")) {
                List<String> methods = Ast.attribute(annotation, "method").stream()
                        .map(e -> Ast.simpleName(e.toString()))
                        .toList();
                return Optional.of(new Mapping(methods.isEmpty() ? List.of("ANY") : methods, paths(annotation)));
            }
        }
        return Optional.empty();
    }

    private List<String> paths(AnnotationExpr annotation) {
        List<Expression> expressions = Ast.attribute(annotation, "value", "path");
        if (expressions.isEmpty()) {
            return List.of("");
        }
        Set<String> paths = new LinkedHashSet<>();
        for (Expression expression : expressions) {
            Set<String> values = resolver.strings(expression, ValueResolver.Ctx.root(null));
            paths.addAll(values.isEmpty() ? Set.of(UNRESOLVED_SEGMENT) : values);
        }
        return List.copyOf(paths);
    }

    private List<Param> params(CallableDeclaration<?> method) {
        List<Param> params = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            String type = Ast.typeName(parameter.getType());
            String name = parameter.getNameAsString();
            String source = null;
            for (AnnotationExpr annotation : parameter.getAnnotations()) {
                source = PARAM_SOURCES.get(Ast.simpleName(annotation.getNameAsString()));
                if (source != null) {
                    String explicit = first(Ast.attribute(annotation, "value", "name"));
                    if (explicit != null && !explicit.isBlank()) {
                        name = explicit;
                    }
                    break;
                }
            }
            if (source == null) {
                source = FRAMEWORK_TYPES.contains(type) ? "FRAMEWORK" : QUERY_TYPES.contains(type) ? "QUERY" : "IMPLICIT";
            }
            params.add(new Param(name, type, source));
        }
        return List.copyOf(params);
    }

    private String first(List<Expression> expressions) {
        for (Expression expression : expressions) {
            Set<String> values = resolver.strings(expression, ValueResolver.Ctx.root(null));
            if (!values.isEmpty()) {
                return values.iterator().next();
            }
        }
        return null;
    }
}
