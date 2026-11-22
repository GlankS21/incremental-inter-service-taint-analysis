package vkr.plugin;

import micans.plugin.spring.MicroserviceHolder;
import micans.plugin.spring.MockObjUtils;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.language.classes.JMethod;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Taints the {@code String} parameters of web endpoints and {@code @RabbitListener} methods.
 *
 * <p>Micans taints only the String fields of DTO parameters, so a value a Feign client sends as a
 * {@code @RequestParam String} or a message whose payload is a String enters the receiving service
 * untainted when that service is analysed alone (in a whole-system run the taint arrives through
 * the call edge). These sources stand for the receiving end of a channel; composition starts chains
 * only at Micans' own sources, so a flow from such a parameter counts only as the continuation of
 * a chain that reached it.
 */
public class StringParamSources implements Plugin {

    private static final String RABBIT_LISTENER = "org.springframework.amqp.rabbit.annotation.RabbitListener";

    private Solver solver;

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    @Override
    public void onStart() {
        Set<JMethod> methods = new LinkedHashSet<>(MicroserviceHolder.getWebEndpoints());
        MicroserviceHolder.getAllHolders().forEach(h -> h.getBeanManager().getAllBeans()
                .forEach(b -> b.getJClass().getDeclaredMethods().stream()
                        .filter(m -> !m.isAbstract() && m.hasAnnotation(RABBIT_LISTENER))
                        .forEach(methods::add)));
        var empty = solver.getContextSelector().getEmptyContext();
        var string = solver.getTypeSystem().getClassType("java.lang.String");
        int count = 0;
        for (JMethod m : methods) {
            for (int i = 0; i < m.getParamCount(); i++) {
                if (string.equals(m.getParamType(i))) {
                    solver.addVarPointsTo(empty, m.getIR().getParam(i),
                            MockObjUtils.getParamTaintObj(solver, m, i, string));
                    count++;
                }
            }
        }
        System.out.println("StringParamSources: " + count + " String parameter source(s)");
    }
}
