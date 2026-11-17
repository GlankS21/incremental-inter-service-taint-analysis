package vkr.plugin;

import micans.plugin.spring.MicroserviceHolder;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.ir.stmt.FieldStmt;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.Stmt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

/**
 * Writes the application classes the analysed service reaches, one top-level class name per line
 * (nested and anonymous classes count as their top-level class, which is how source files map to
 * classes). A commit that changes no class of this set leaves the service's analysis result as it
 * is, apart from new classes and build changes, which the replay treats conservatively.
 *
 * <p>Enabled with system property {@code vkr.reachableClasses=<output file>}.
 */
public class ReachableClasses implements Plugin {

    private Solver solver;

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    @Override
    public void onFinish() {
        String out = System.getProperty("vkr.reachableClasses");
        if (out == null) {
            return;
        }
        var names = new java.util.HashSet<String>();
        solver.getCallGraph().reachableMethods().map(m -> m.getMethod()).distinct().forEach(m -> {
            names.add(m.getDeclaringClass().getName());
            if (m.isAbstract() || m.isNative() || !MicroserviceHolder.isApplication(m.getDeclaringClass().getName())) {
                return;
            }
            // classes the reachable code refers to without reaching a method of them: interfaces such
            // as MyBatis mappers and Feign clients, constants, types of fields it reads or writes
            for (Stmt s : m.getIR()) {
                if (s instanceof Invoke i && !i.isDynamic()) {
                    names.add(i.getMethodRef().getDeclaringClass().getName());
                } else if (s instanceof New n) {
                    names.add(n.getRValue().getType().getName());
                } else if (s instanceof FieldStmt<?, ?> f) {
                    names.add(f.getFieldRef().getDeclaringClass().getName());
                }
            }
        });
        var classes = new TreeSet<String>();
        names.stream()
                .filter(MicroserviceHolder::isApplication)
                .map(name -> name.contains("$") ? name.substring(0, name.indexOf('$')) : name)
                .forEach(classes::add);
        try {
            Files.write(Path.of(out), classes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("ReachableClasses: " + classes.size() + " application class(es) -> " + out);
    }
}
