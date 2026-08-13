# Repository Instructions

## Required verification after code changes

After modifying source code, tests, resources, or build configuration, always run the complete Maven package build before reporting the task as complete:

```cmd
mvn package
```

Do not substitute `mvn test` for this step. The package build must complete successfully and produce the application JAR in `target/`. Report the build result and artifact path in the completion summary.
