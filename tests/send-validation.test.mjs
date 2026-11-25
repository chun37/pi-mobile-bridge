import test from "node:test";
import assert from "node:assert/strict";
import { validateSend } from "../extension/send-validation.ts";

const png = { mimeType: "image/png", data: "aGVsbG8=" };
const valid = (extra = {}) => ({ text: "hello", clientMsgId: "id-1", ...extra });
const code = (p, expected) => assert.throws(() => validateSend(p), (e) => e.message === expected);

test("a plain message passes through with its fingerprint and no images", () => {
  const r = validateSend(valid());
  assert.equal(r.text, "hello");
  assert.equal(r.clientMsgId, "id-1");
  assert.deepEqual(r.images, []);
  assert.equal(r.deliverAs, undefined);
  assert.equal(r.fingerprint, JSON.stringify(["hello", [], undefined]));
});

test("validation keeps the relay error codes and their order", () => {
  code(valid({ text: 1 }), "invalid_text");
  code(valid({ text: "x".repeat(1000001) }), "invalid_text");
  code(valid({ clientMsgId: "" }), "invalid_clientMsgId");
  code(valid({ clientMsgId: "x".repeat(129) }), "invalid_clientMsgId");
  code(valid({ clientMsgId: undefined }), "invalid_clientMsgId");
  code(valid({ deliverAs: "now" }), "invalid_delivery");
  code(valid({ images: "no" }), "invalid_images");
  code(valid({ images: [png, png, png, png, png] }), "invalid_images");
  code(valid({ images: [{ mimeType: "image/svg+xml", data: "aGVsbG8=" }] }), "invalid_image");
  code(valid({ images: [{ mimeType: "image/png", data: "not base64!" }] }), "invalid_image");
  code(valid({ text: "   " }), "empty_message");
  assert.equal(validateSend(valid({ text: "   ", images: [png] })).images.length, 1);
});

test("deliverAs accepts only steer and followUp and is preserved in the fingerprint", () => {
  for (const deliverAs of ["steer", "followUp"]) {
    const r = validateSend(valid({ deliverAs, images: [png] }));
    assert.equal(r.deliverAs, deliverAs);
    assert.equal(r.fingerprint, JSON.stringify(["hello", [png], deliverAs]));
  }
});
