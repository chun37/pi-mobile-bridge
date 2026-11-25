export type SendRequest = {
  text: string;
  clientMsgId: string;
  images: any[];
  deliverAs?: "steer" | "followUp";
  fingerprint: string;
};

const IMAGE_TYPES = ["image/png", "image/jpeg", "image/webp", "image/gif"];
const MAX_IMAGES = 4;
const MAX_IMAGE_CHARS = 6 * 1024 * 1024;

/** Validate a relay "send" payload and derive its deduplication fingerprint.
 * Throws the relay's error codes in the same order as before extraction; reads no Pi state.
 */
export function validateSend(p: any): SendRequest {
  if (typeof p.text !== "string" || p.text.length > 1000000)
    throw new Error("invalid_text");
  if (typeof p.clientMsgId !== "string" || !p.clientMsgId || p.clientMsgId.length > 128)
    throw new Error("invalid_clientMsgId");
  if (p.deliverAs && !["steer", "followUp"].includes(p.deliverAs))
    throw new Error("invalid_delivery");
  const images = p.images ?? [];
  if (!Array.isArray(images) || images.length > MAX_IMAGES)
    throw new Error("invalid_images");
  for (const im of images) {
    if (
      !IMAGE_TYPES.includes(im.mimeType) ||
      typeof im.data !== "string" ||
      im.data.length > MAX_IMAGE_CHARS ||
      !/^[A-Za-z0-9+/]*={0,2}$/.test(im.data)
    )
      throw new Error("invalid_image");
  }
  if (!p.text.trim() && !images.length) throw new Error("empty_message");
  return {
    text: p.text,
    clientMsgId: p.clientMsgId,
    images,
    deliverAs: p.deliverAs as "steer" | "followUp" | undefined,
    fingerprint: JSON.stringify([p.text, images, p.deliverAs]),
  };
}
