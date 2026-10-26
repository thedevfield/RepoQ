plugins {
    base
}

tasks.named("build") {
    dependsOn(":project-service:build", ":interview-service:build")
}
