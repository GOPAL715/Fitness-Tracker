'use strict';

/**
 * RFC 4122 version 5 (SHA-1, name-based) UUID generation.
 *
 * Implemented locally with Node's built-in crypto so the generator has no
 * runtime dependencies and produces identical output on any platform. This is
 * the standard algorithm: SHA-1(namespace_bytes || name), then set the version
 * and variant bits.
 */

const crypto = require('crypto');

function parseUuid(uuid) {
  const hex = uuid.replace(/-/g, '');
  if (!/^[0-9a-fA-F]{32}$/.test(hex)) {
    throw new Error(`Invalid UUID: ${uuid}`);
  }
  return Buffer.from(hex, 'hex');
}

function formatUuid(buf) {
  const hex = buf.toString('hex');
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20, 32),
  ].join('-');
}

function v5(namespaceUuid, name) {
  const ns = parseUuid(namespaceUuid);
  const nameBytes = Buffer.from(String(name), 'utf8');
  const hash = crypto.createHash('sha1').update(ns).update(nameBytes).digest();

  const out = Buffer.from(hash.subarray(0, 16));
  // Version 5 in the high nibble of octet 6.
  out[6] = (out[6] & 0x0f) | 0x50;
  // RFC 4122 variant in the two high bits of octet 8.
  out[8] = (out[8] & 0x3f) | 0x80;
  return formatUuid(out);
}

module.exports = { v5, parseUuid, formatUuid };
