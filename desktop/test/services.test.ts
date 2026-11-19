import { strict as assert } from "node:assert";
import { test } from "node:test";
import { classifyService, prettyServiceName } from "../src/main/services";

test("prettyServiceName strips prefix/suffix and title-cases", () => {
  assert.equal(prettyServiceName("access-control-service"), "Access Control");
  assert.equal(prettyServiceName("dashboard-service"), "Dashboard");
  assert.equal(prettyServiceName("texera-postgres"), "Postgres");
});

test("prettyServiceName keeps special casing", () => {
  assert.equal(prettyServiceName("texera-grafana"), "Grafana");
  assert.equal(prettyServiceName("litellm"), "LiteLLM");
  assert.equal(prettyServiceName("rustfs"), "RustFS");
});

test("classifyService groups by keyword", () => {
  assert.equal(classifyService("texera-grafana").group, "Observability");
  assert.equal(classifyService("texera-postgres").group, "Data & storage");
  assert.equal(classifyService("workflow-compiling-service").group, "Workflow engine");
  assert.equal(classifyService("texera-jupyter").group, "AI & notebooks");
  assert.equal(classifyService("dashboard-service").group, "Core services");
});
