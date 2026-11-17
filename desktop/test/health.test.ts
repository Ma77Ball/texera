import { strict as assert } from "node:assert";
import { test } from "node:test";
import { ContainerHealth, overallStatus } from "../src/main/compose";

function c(state: string, health = ""): ContainerHealth {
  return { name: "x", service: "x", state, health };
}

test("overallStatus is stopped when nothing runs", () => {
  assert.equal(overallStatus([]), "stopped");
  assert.equal(overallStatus([c("exited"), c("created")]), "stopped");
});

test("overallStatus is starting while any running container is still starting", () => {
  assert.equal(overallStatus([c("running", "healthy"), c("running", "starting")]), "starting");
});

test("overallStatus is running when all running containers are settled", () => {
  assert.equal(overallStatus([c("running", "healthy"), c("running", "")]), "running");
});
