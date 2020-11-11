#
# Copyright (C) 2020 Grakn Labs
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as
# published by the Free Software Foundation, either version 3 of the
# License, or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#

load("@graknlabs_dependencies//tool/release:rules.bzl", "release_validate_deps")
load("@graknlabs_dependencies//tool/checkstyle:rules.bzl", "checkstyle_test")
load("@graknlabs_dependencies//builder/java:rules.bzl", "native_java_libraries")

exports_files(
    ["VERSION", "deployment.bzl", "RELEASE_TEMPLATE.md", "LICENSE", "README.md"],
)

native_java_libraries(
    name = "grakn",
    srcs = glob(["*.java"]),
    deps = [
        # Internal dependencies
        "//common:common",
    ],
    native_libraries_deps = [
        "//query:query",
        "//concept:concept",
    ],
    tags = ["maven_coordinates=io.grakn.core:grakn-core:{pom_version}"],
    visibility = ["//visibility:public"],
)

release_validate_deps(
    name = "release-validate-deps",
    refs = "@graknlabs_grakn_core_workspace_refs//:refs.json",
    tagged_deps = [
        "@graknlabs_common",
        "@graknlabs_graql",
        "@graknlabs_protocol",
    ],
    tags = ["manual"]  # in order for bazel test //... to not fail
)

checkstyle_test(
    name = "checkstyle",
    include = glob(["*", ".grabl/*"]),
    exclude = glob(["docs/*"]),
    license_type = "agpl",
)

# CI targets that are not declared in any BUILD file, but are called externally
filegroup(
    name = "ci",
    data = [
        "@graknlabs_dependencies//image/rbe:ubuntu-1604",
        "@graknlabs_dependencies//library/maven:update",
        "@graknlabs_dependencies//tool/checkstyle:test-coverage",
        "@graknlabs_dependencies//tool/sonarcloud:code-analysis",
        "@graknlabs_dependencies//tool/unuseddeps:unused-deps",
    ],
)
