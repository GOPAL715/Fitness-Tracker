'use strict';

/**
 * Download and verify the pinned USDA SR Legacy artifact, then extract it.
 *
 * Verifies the ZIP sha256 BEFORE extracting and aborts on mismatch, so a
 * corrupted or substituted download can never reach the generator.
 *
 * Usage: node fetch-source.cjs [workDir]
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { execFileSync } = require('child_process');
const C = require('./config.cjs');

const workDir = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.join(os.tmpdir(), 'fittrack-food-catalog');
const zipPath = path.join(workDir, 'FoodData_Central_sr_legacy_food_csv_2018-04.zip');
const extractDir = path.join(workDir, 'extracted');

function sha256File(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex').toUpperCase();
}

function main() {
  fs.mkdirSync(workDir, { recursive: true });

  if (fs.existsSync(zipPath)) {
    const existing = sha256File(zipPath);
    if (existing === C.SOURCE.zipSha256) {
      process.stdout.write(`cached zip verified: ${existing}\n`);
    } else {
      process.stdout.write(`cached zip mismatch, re-downloading\n`);
      fs.unlinkSync(zipPath);
      download();
    }
  } else {
    download();
  }

  fs.rmSync(extractDir, { recursive: true, force: true });
  fs.mkdirSync(extractDir, { recursive: true });
  execFileSync('tar', ['-xf', zipPath, '-C', extractDir], { stdio: 'inherit' });

  const inner = path.join(extractDir, C.SOURCE.innerDir);
  if (!fs.existsSync(inner)) {
    throw new Error(`expected inner directory missing: ${C.SOURCE.innerDir}`);
  }
  process.stdout.write(`extracted to: ${extractDir}\n`);
  process.stdout.write(`pass this path to the generator: ${extractDir}\n`);
}

function download() {
  process.stdout.write(`downloading: ${C.SOURCE.url}\n`);
  const ps =
    `$ProgressPreference='SilentlyContinue';` +
    `Invoke-WebRequest -Uri '${C.SOURCE.url}' -OutFile '${zipPath}' -UseBasicParsing`;
  execFileSync('powershell.exe', ['-NoProfile', '-Command', ps], { stdio: 'inherit' });

  const actual = sha256File(zipPath);
  if (actual !== C.SOURCE.zipSha256) {
    fs.unlinkSync(zipPath);
    throw new Error(
      `ZIP SHA-256 MISMATCH\n  expected ${C.SOURCE.zipSha256}\n  actual   ${actual}\n` +
        'Download aborted; nothing was extracted.'
    );
  }
  process.stdout.write(`zip sha256 verified: ${actual}\n`);
}

main();
