/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.gradle.internal;

import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin;

import org.elasticsearch.gradle.VersionProperties;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.external.javadoc.StandardJavadocDocletOptions;

import java.util.Comparator;
import java.util.List;

// Handle javadoc dependencies across projects. Order matters: the linksOffline for
// org.elasticsearch:elasticsearch must be the last one or all the links for the
// other packages (e.g org.elasticsearch.client) will point to server rather than
// their own artifacts.
public class ElasticsearchJavadocPlugin implements Plugin<Project> {
    private Project project;

    @Override
    public void apply(Project project) {
        this.project = project;

        // ignore missing javadocs
        project.getTasks().withType(Javadoc.class).configureEach(javadoc -> {
            // the -quiet here is because of a bug in gradle, in that adding a string option
            // by itself is not added to the options. By adding quiet, both this option and
            // the "value" -quiet is added, separated by a space. This is ok since the javadoc
            // command already adds -quiet, so we are just duplicating it
            // see https://discuss.gradle.org/t/add-custom-javadoc-option-that-does-not-take-an-argument/5959
            javadoc.getOptions().setEncoding("UTF8");
            ((StandardJavadocDocletOptions) javadoc.getOptions()).addStringOption("Xdoclint:all,-missing", "-quiet");
        });

        // TODO revert the dependency and just apply the javadoc plugin in the build plugin later on
        project.afterEvaluate(project1 -> {
            var withShadowPlugin = project1.getPlugins().hasPlugin(ShadowPlugin.class);
            var configurations = withShadowPlugin
                ? List.of("compileClasspath", "compileOnly", "shadow")
                : List.of("compileClasspath", "compileOnly");
            configurations.forEach(configName -> {
                Configuration configuration = project1.getConfigurations().getByName(configName);
                configuration.getDependencies()
                    .stream()
                    .sorted(Comparator.comparing(Dependency::getGroup))
                    .filter(d -> d instanceof ProjectDependency)
                    .map(d -> (ProjectDependency) d)
                    .filter(p -> p.getDependencyProject() != null)
                    .forEach(projectDependency -> configureDependency(withShadowPlugin, projectDependency));
            });
        });
    }

    private void configureDependency(boolean shadowed, ProjectDependency dep) {
        var upstreamProject = dep.getDependencyProject();
        if (shadowed) {
            /*
             * Include the source of shadowed upstream projects so we don't
             * have to publish their javadoc.
             */
            project.evaluationDependsOn(upstreamProject.getPath());
            project.getTasks().named("javadoc", Javadoc.class).configure(javadoc -> {
                Javadoc upstreamJavadoc = upstreamProject.getTasks().named("javadoc", Javadoc.class).get();
                javadoc.setSource(javadoc.getSource().plus(upstreamJavadoc.getSource()));
                javadoc.setClasspath(upstreamJavadoc.getClasspath());
            });
            /*
             * Instead we need the upstream project's javadoc classpath so
             * we don't barf on the classes that it references.
             */
        } else {
            project.getTasks().named("javadoc", Javadoc.class).configure(javadoc -> {
                // Link to non-shadowed dependant projects
                javadoc.dependsOn(upstreamProject.getPath() + ":javadoc");
                String externalLinkName = upstreamProject.getExtensions().getByType(BasePluginExtension.class).getArchivesName().get();
                String artifactPath = dep.getGroup().replaceAll("\\.", "/") + '/' + externalLinkName.replaceAll("\\.", "/") + '/' + dep
                    .getVersion();
                var options = (StandardJavadocDocletOptions) javadoc.getOptions();
                options.linksOffline(artifactHost() + "/javadoc/" + artifactPath, "${upstreamProject.buildDir}/docs/javadoc/");
            });
        }
    }

    private String artifactHost() {
        return VersionProperties.getElasticsearch().endsWith("-SNAPSHOT") ? "https://snapshots.elastic.co" : "https://artifacts.elastic.co";
    }
}
