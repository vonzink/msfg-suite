package com.msfg.los.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Audit finding S1 — module-boundary coupling.
 *
 * <p>A feature module must reach another module's data only through that module's <em>service</em>,
 * never by injecting the other module's JPA repository directly. This guard forbids any class in
 * module package {@code com.msfg.los.<A>} from depending on a repository type in
 * {@code com.msfg.los.<B>..repo..} when {@code A != B}.
 *
 * <p>Scope is intentionally narrow: it targets <strong>repositories only</strong>. Cross-module
 * domain/dto imports are out of scope and allowed. A class may freely use its OWN module's repo.
 * {@code platform} and {@code loan} (loan-core) are shared foundation modules — depending on their
 * packages is always allowed (they expose no domain repositories consumers reach across, but they
 * are excluded from the "different module" check regardless).
 */
class ModuleBoundaryTest {

    /** {@code com.msfg.los.<module>.<...>} — capture the module segment. */
    private static final Pattern MODULE = Pattern.compile("^com\\.msfg\\.los\\.([a-z_]+)(?:\\..*)?$");

    /** Shared foundation modules; depending on these never counts as a cross-module violation. */
    private static final Set<String> SHARED = Set.of("platform", "loan");

    @Test
    void noModuleDependsOnAnotherModulesRepositories() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.msfg.los");

        ArchRule rule = classes()
                .should(notDependOnAnotherModulesRepository())
                .because("cross-module reads must go through the owning module's service, not its "
                        + "repository (audit finding S1)");

        rule.check(classes);
    }

    private static ArchCondition<JavaClass> notDependOnAnotherModulesRepository() {
        return new ArchCondition<>("not depend on another module's repository") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originModule = moduleOf(origin.getFullName());
                if (originModule == null) {
                    return;
                }
                origin.getDirectDependenciesFromSelf().forEach(dep -> {
                    JavaClass target = dep.getTargetClass();
                    String targetName = target.getFullName();
                    if (!isRepository(targetName)) {
                        return;
                    }
                    String targetModule = moduleOf(targetName);
                    if (targetModule == null || SHARED.contains(targetModule)) {
                        return;
                    }
                    if (!targetModule.equals(originModule)) {
                        events.add(SimpleConditionEvent.violated(origin,
                                originModule + " class " + origin.getName()
                                        + " depends on " + targetModule + " repository "
                                        + target.getName() + " — route through the "
                                        + targetModule + " service instead"));
                    }
                });
            }
        };
    }

    /** A repository is any type whose package is {@code com.msfg.los.<module>.repo} (or below). */
    private static boolean isRepository(String fullyQualifiedName) {
        return fullyQualifiedName.matches("^com\\.msfg\\.los\\.[a-z_]+\\.repo(?:\\..*)?\\.[^.]+$");
    }

    private static String moduleOf(String fullyQualifiedName) {
        Matcher m = MODULE.matcher(fullyQualifiedName);
        return m.matches() ? m.group(1) : null;
    }
}
