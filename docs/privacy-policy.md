# Privacy Policy

Effective date: 2026-04-19

This privacy policy describes how Alipsa Accounting handles information when you use the desktop application and related project resources.

## Summary

Alipsa Accounting is a desktop accounting application built to store accounting data locally on the user's machine.

The application does not include advertising, analytics, telemetry, or background uploads of accounting records, attachments, report archives, backups, or SIE files to project-operated servers.

## Data stored locally

The application stores accounting and operational data locally in the user's application data directory, including:

- accounting database content
- attachments
- report archives
- backups created by the user
- logs and generated documentation
- downloaded update packages, if the user chooses to install an update

These files remain on the local system until they are deleted by the user or removed through application features. Accounting data may also be subject to statutory retention requirements applicable to the user.

## Network activity

Alipsa Accounting is primarily a local-first desktop application. Network access is limited to the following cases:

### Automatic update check

If automatic update checks are enabled, the app performs a background check on startup against the GitHub Releases API for the `Alipsa/accounting` repository to determine whether a newer version is available.

This request may disclose technical request metadata to GitHub, including:

- the user's IP address
- HTTP headers required to make the request
- a `User-Agent` header containing the application name and current version

No accounting content, attachments, backups, or report data are sent as part of this update check.

Users can disable automatic update checks in the application's Settings tab and use the Help menu to perform a manual check when desired.

### User-initiated update download

If the user chooses to download and install an update, the application downloads the release archive and checksum files from GitHub-hosted release assets.

Only the files required for the selected update are downloaded.

### User-initiated browser actions

If the user chooses to report an issue, the application opens the project's GitHub issue page in the system browser. Any information subsequently submitted there is governed by GitHub's own terms and privacy practices.

## Third-party services

The current application uses GitHub for release metadata, update downloads, and issue tracking:

- GitHub Releases API
- GitHub release asset hosting
- GitHub Issues

GitHub's privacy statement is available at:

- https://docs.github.com/privacy/

## What is not transferred

Except for the GitHub interactions described above, the application does not transfer information to external networked systems on its own.

In particular, the application does not upload accounting data, vouchers, attachments, reports, backups, or imported/exported files to project-operated services.

## Changes to this policy

This policy may be updated when the application's network behavior changes or when new third-party services are introduced.

## Contact

Project home page:

- https://github.com/Alipsa/accounting

Issue tracker:

- https://github.com/Alipsa/accounting/issues
