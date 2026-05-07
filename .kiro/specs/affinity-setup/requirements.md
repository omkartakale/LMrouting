# Requirements Document

## Introduction

The Affinity Setup feature enables hub managers at PNQ HDP to permanently define and persist SR-to-pincode assignments. Currently, SR affinities are stored only in memory and are lost on every application restart — managers must re-enter them each time. This feature introduces a persistent storage layer (JSON file on disk), auto-loads saved affinities when the application starts, and provides a dedicated Affinity Setup UI (`affinity-setup.html`) where managers can draw regions on a Leaflet.js map, assign SRs to those regions, save the configuration, and edit it later. The existing `index.html` SR Affinity panel is also updated to auto-load saved affinities on page load.

## Glossary

- **Affinity_Config**: The persistent mapping of SR names to their assigned pincode sets, stored as a JSON file on disk.
- **AffinityConfigStorageService**: Spring Boot service responsible for reading and writing the Affinity_Config JSON file.
- **AffinityConfigStorageController**: Spring Boot REST controller exposing endpoints to save, load, and clear the Affinity_Config.
- **AffinityMatchingController**: Existing Spring Boot controller that holds the in-memory `srAffinities` map and runs affinity-based allocation.
- **SR**: Service Representative — one of SR-001 through SR-015 defined in `application.properties`.
- **Pincode**: A 6-digit India Post pincode corresponding to a geographic boundary polygon in `pune-pincode-boundaries.geojson`.
- **Hub_Manager**: The operations manager who configures SR-to-pincode assignments via the UI.
- **Affinity_Setup_Page**: The dedicated `affinity-setup.html` page for permanent affinity configuration.
- **SR_Affinity_Panel**: The existing affinity section inside `index.html` used during daily allocation.
- **Custom_Region**: A polygon drawn on the Leaflet.js map by the Hub_Manager to group pincodes.
- **Storage_File**: The JSON file at a configurable path (default: `data/affinity-config.json`) where the Affinity_Config is persisted.

---

## Requirements

### Requirement 1: Persistent Affinity Configuration Storage

**User Story:** As a Hub_Manager, I want SR-to-pincode assignments to be saved to disk, so that I do not need to re-enter them every time the application restarts.

#### Acceptance Criteria

1. THE AffinityConfigStorageService SHALL write the Affinity_Config as a JSON file to the Storage_File path when a save operation is requested.
2. THE AffinityConfigStorageService SHALL read the Affinity_Config from the Storage_File path when a load operation is requested.
3. IF the Storage_File does not exist when a load is attempted, THEN THE AffinityConfigStorageService SHALL return an empty Affinity_Config without throwing an error.
4. IF the Storage_File contains malformed JSON, THEN THE AffinityConfigStorageService SHALL log a warning and return an empty Affinity_Config.
5. THE AffinityConfigStorageService SHALL create all necessary parent directories before writing the Storage_File.
6. THE AffinityConfigStorageService SHALL store both SR-to-pincode affinities and Custom_Region polygon coordinates in the same Storage_File.

---

### Requirement 2: Auto-Load Affinities on Application Startup

**User Story:** As a Hub_Manager, I want saved affinities to be loaded automatically when the application starts, so that the system is ready to use without manual intervention.

#### Acceptance Criteria

1. WHEN the Spring Boot application starts, THE AffinityMatchingController SHALL load the Affinity_Config from the Storage_File into the in-memory `srAffinities` map.
2. WHEN the Spring Boot application starts, THE AffinityMatchingController SHALL load saved Custom_Region polygons from the Storage_File into the in-memory `customRegions` map.
3. IF no Storage_File exists at startup, THEN THE AffinityMatchingController SHALL start with empty in-memory maps and log an informational message.
4. WHEN affinities are successfully loaded at startup, THE AffinityMatchingController SHALL log the number of SRs loaded and the number of Custom_Regions loaded.

---

### Requirement 3: Save Affinities After Every Update

**User Story:** As a Hub_Manager, I want affinity changes to be automatically persisted whenever I update them, so that my configuration is never lost.

#### Acceptance Criteria

1. WHEN the `POST /api/affinity-match/set` endpoint is called successfully, THE AffinityMatchingController SHALL persist the updated `srAffinities` map to the Storage_File via AffinityConfigStorageService.
2. WHEN the `POST /api/affinity-match/set-custom-regions` endpoint is called successfully, THE AffinityMatchingController SHALL persist the updated `customRegions` map to the Storage_File via AffinityConfigStorageService.
3. WHEN the `DELETE /api/affinity-match/clear` endpoint is called, THE AffinityMatchingController SHALL persist the cleared (empty) Affinity_Config to the Storage_File.
4. IF a persistence write fails, THEN THE AffinityMatchingController SHALL log the error and return an HTTP 500 response with a descriptive error message.

---

### Requirement 4: REST API for Affinity Configuration Management

**User Story:** As a Hub_Manager, I want REST endpoints to save, load, and clear the persisted affinity configuration, so that the UI can interact with the backend reliably.

#### Acceptance Criteria

1. THE AffinityConfigStorageController SHALL expose a `POST /api/affinity-config/save` endpoint that accepts an Affinity_Config payload and writes it to the Storage_File.
2. THE AffinityConfigStorageController SHALL expose a `GET /api/affinity-config/load` endpoint that reads and returns the Affinity_Config from the Storage_File.
3. THE AffinityConfigStorageController SHALL expose a `DELETE /api/affinity-config/clear` endpoint that deletes the Storage_File and returns a success response.
4. WHEN `GET /api/affinity-config/load` is called and no Storage_File exists, THE AffinityConfigStorageController SHALL return HTTP 200 with an empty Affinity_Config object (not HTTP 404).
5. THE AffinityConfigStorageController SHALL return HTTP 200 with a `{ "success": true }` body on successful save and clear operations.

---

### Requirement 5: Dedicated Affinity Setup Page

**User Story:** As a Hub_Manager, I want a dedicated page to define SR-to-pincode assignments permanently, so that I have a clear, focused interface separate from the daily allocation workflow.

#### Acceptance Criteria

1. THE Affinity_Setup_Page SHALL be served as a static HTML file at `/affinity-setup.html` by the Spring Boot application.
2. THE Affinity_Setup_Page SHALL display a full-screen Leaflet.js map centered on the PNQ HDP hub (lat 18.4600561, lng 73.8884305) at zoom level 11.
3. THE Affinity_Setup_Page SHALL load and render all 120 Pune pincode boundary polygons from the `/api/pincode-boundaries` endpoint on map load.
4. WHEN the Affinity_Setup_Page loads, THE Affinity_Setup_Page SHALL call `GET /api/affinity-config/load` and highlight pincodes already assigned to each SR using distinct colors.
5. THE Affinity_Setup_Page SHALL display a sidebar listing all 15 SRs (SR-001 through SR-015) with their currently assigned pincodes.
6. WHEN a Hub_Manager clicks a pincode polygon on the map, THE Affinity_Setup_Page SHALL show a popup allowing the Hub_Manager to assign that pincode to a selected SR or remove an existing assignment.
7. WHEN a Hub_Manager clicks "Save Configuration", THE Affinity_Setup_Page SHALL call `POST /api/affinity-config/save` with the current assignments and display a success or error message.
8. WHEN a Hub_Manager clicks "Clear All", THE Affinity_Setup_Page SHALL call `DELETE /api/affinity-config/clear` and reset the map to show no assignments.
9. THE Affinity_Setup_Page SHALL provide a link back to the main `index.html` allocation page.

---

### Requirement 6: Edit Existing Affinity Assignments

**User Story:** As a Hub_Manager, I want to edit existing SR-to-pincode assignments on the Affinity Setup page, so that I can update the configuration as operational needs change.

#### Acceptance Criteria

1. WHEN the Affinity_Setup_Page loads saved assignments, THE Affinity_Setup_Page SHALL render each assigned pincode polygon in the color corresponding to its assigned SR.
2. WHEN a Hub_Manager clicks an already-assigned pincode polygon, THE Affinity_Setup_Page SHALL show the current SR assignment in the popup and allow reassignment to a different SR.
3. WHEN a Hub_Manager reassigns a pincode from one SR to another, THE Affinity_Setup_Page SHALL update the polygon color immediately to reflect the new assignment without requiring a page reload.
4. WHEN a Hub_Manager removes a pincode assignment, THE Affinity_Setup_Page SHALL revert the polygon to the default unassigned color immediately.
5. THE Affinity_Setup_Page SHALL display a count of assigned pincodes per SR in the sidebar, updating in real time as assignments change.

---

### Requirement 7: Auto-Load Saved Affinities in the Main Allocation Page

**User Story:** As a Hub_Manager, I want the SR Affinity panel in `index.html` to automatically show saved affinities when the page loads, so that I do not need to manually re-enter pincodes before running allocation.

#### Acceptance Criteria

1. WHEN `index.html` loads, THE SR_Affinity_Panel SHALL call `GET /api/affinity-config/load` and populate each SR's pincode input field with the saved pincodes.
2. WHEN `index.html` loads and no saved affinities exist, THE SR_Affinity_Panel SHALL display empty pincode input fields without showing an error.
3. THE SR_Affinity_Panel SHALL display a "Load Saved Affinities" button that, when clicked, re-fetches and re-populates the pincode fields from `GET /api/affinity-config/load`.
4. THE SR_Affinity_Panel SHALL display a "Go to Affinity Setup" link that navigates the Hub_Manager to `affinity-setup.html`.
5. WHEN affinities are loaded successfully into the SR_Affinity_Panel, THE SR_Affinity_Panel SHALL display a status message indicating how many SRs have saved affinities.

---

### Requirement 8: Storage File Path Configuration

**User Story:** As a developer, I want the Storage_File path to be configurable via `application.properties`, so that the file location can be changed without modifying code.

#### Acceptance Criteria

1. THE AffinityConfigStorageService SHALL read the Storage_File path from the `affinity.config.storage.path` property in `application.properties`.
2. WHERE the `affinity.config.storage.path` property is not set, THE AffinityConfigStorageService SHALL use `data/affinity-config.json` as the default path.
3. THE `application.properties` file SHALL include the `affinity.config.storage.path` property with the default value `data/affinity-config.json` and a descriptive comment.
