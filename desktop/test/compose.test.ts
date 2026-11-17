import { strict as assert } from "node:assert";
import { test } from "node:test";
import {
  buildComposeArgs,
  ComposeOptions,
  composeFiles,
  parseComposePs
} from "../src/main/compose";

const opts: ComposeOptions = {
  projectName: "texera-single-node",
  composeDir: "/opt/texera/single-node",
  withObservability: true
};

test("composeFiles includes the overlay only when observability is on", () => {
  assert.deepEqual(composeFiles(false), ["docker-compose.yml"]);
  assert.deepEqual(composeFiles(true), ["docker-compose.yml", "docker-compose.observability.yml"]);
});

test("buildComposeArgs wires project, files, and sub-command in order", () => {
  const args = buildComposeArgs(opts, ["up", "-d"]);
  assert.deepEqual(args, [
    "compose",
    "-p",
    "texera-single-node",
    "-f",
    "docker-compose.yml",
    "-f",
    "docker-compose.observability.yml",
    "up",
    "-d"
  ]);
});

test("buildComposeArgs drops the overlay when observability is off", () => {
  const args = buildComposeArgs({ ...opts, withObservability: false }, ["down"]);
  assert.deepEqual(args, ["compose", "-p", "texera-single-node", "-f", "docker-compose.yml", "down"]);
});

test("parseComposePs handles NDJSON output", () => {
  const out =
    '{"Name":"file-service","Service":"file-service","State":"running","Health":"healthy"}\n' +
    '{"Name":"texera-postgres","Service":"texera-postgres","State":"running","Health":""}';
  const rows = parseComposePs(out);
  assert.equal(rows.length, 2);
  assert.equal(rows[0].service, "file-service");
  assert.equal(rows[0].health, "healthy");
  assert.equal(rows[1].state, "running");
});

test("parseComposePs handles a JSON array", () => {
  const out = '[{"Name":"a","Service":"a","State":"running","Health":"starting"}]';
  const rows = parseComposePs(out);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].health, "starting");
});

test("parseComposePs ignores blank and malformed lines", () => {
  const out = '\n{"Name":"a","Service":"a","State":"running"}\nnot-json\n';
  const rows = parseComposePs(out);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].service, "a");
});
