"""Macros to simplify generating maven files.
"""

# Docs of pom_file rule can be found at: 
# https://github.com/google/bazel-common/blob/master/tools/maven/pom_file.bzl#L177
load("@google_bazel_common//tools/maven:pom_file.bzl", default_pom_file = "pom_file")

def pom_file(name, targets, group_id, artifact_id, **kwargs):
    default_pom_file(
        name = name,
        targets = targets,
        template_file = "//tools:pom_template.xml",
        substitutions = {
            "{group_id}": group_id,
            "{artifact_id}": artifact_id,
        },
        **kwargs
    )
