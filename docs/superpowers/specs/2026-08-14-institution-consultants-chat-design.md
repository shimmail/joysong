# Institution Consultants Chat Design

## Goal

When a user taps **Consult institution** on an institution detail page, load every available consultant for that institution, show their names in a scrollable bottom sheet, and open the existing direct-message page after the user selects one.

## Scope

- Change only the Flutter application.
- Reuse the existing backend endpoint `GET /api/discover/institutions/{institutionId}/consultants`.
- Reuse the existing direct-message creation and navigation flow.
- Do not change backend APIs, database migrations, or unrelated catalog behavior.

## Architecture

Add a small `InstitutionConsultant` domain model with `id` and `name`, matching the existing public endpoint. Extend the discover data source and repository with a method that retrieves the consultants for one institution.

The institution detail view exposes the institution ID through a nullable consultation callback. The discover page and application shell pass this callback through to the existing composition point. The shell loads the consultants, presents the selection sheet, and, after selection, delegates to the existing `_openDirectMessage` flow.

## User Experience

1. The user taps **Consult institution** / **咨询机构**.
2. A modal bottom sheet opens and displays a loading indicator while the consultant request is running.
3. On success, the sheet displays every returned consultant as a selectable row. The list is scrollable and constrained to a practical portion of the screen.
4. Selecting a consultant closes the sheet, creates or retrieves the direct-message conversation, and opens the existing DM thread with the consultant name as its title.
5. If the institution has no available consultants, the sheet displays a localized empty-state message.
6. If the list request fails, the sheet displays a localized error message and a retry action.
7. If DM creation fails, the existing direct-message error handling remains responsible for user feedback.

Repeated taps while the sheet is already being opened must not create multiple selection sheets. Dismissing the sheet during loading must not attempt navigation afterward.

## Components and Data Flow

### Discover data layer

- Parse the endpoint response into immutable `InstitutionConsultant` values.
- Preserve the backend order so the UI matches the service ordering.
- Treat malformed collection entries as invalid rather than manufacturing placeholder users.

### Institution detail view

- Replace the current informational SnackBar behavior with a callback carrying the resolved institution ID.
- Disable the action when no institution ID is available or no callback was supplied.
- Keep the callback nullable so existing catalog preview and management callers remain source-compatible.

### Application shell

- Own the asynchronous request and modal lifecycle because it already owns the repositories and DM navigation methods.
- Render loading, success, empty, and failure states inside one scroll-controlled modal.
- Pop the modal with the selected `InstitutionConsultant`; only then call `_openDirectMessage(consultant.id, title: consultant.name)`.

## Error Handling

- Consultant query failure: remain in the modal and show localized failure text plus retry.
- Empty result: show a localized no-consultants message; do not start a DM.
- Missing institution ID: disable the button rather than issue an invalid request.
- DM creation failure: use the existing `_openDirectMessage` failure path.
- Disposed contexts or dismissed modal: guard mounted/context usage before updating UI or navigating.

## Testing

Follow test-driven development with the smallest related Flutter tests first:

- Data-source/repository parsing of a valid consultant list.
- Institution detail button emits the current institution ID.
- Modal shows all returned consultant names in a scrollable list.
- Selecting one consultant closes the modal and calls the DM-opening callback with the correct ID and name.
- Empty and request-failure states render localized feedback; failure state can retry.

After focused tests pass, run `flutter analyze` once. No backend tests are required because the required endpoint and service filtering already exist and are unchanged.

## Documentation Follow-up

After the implementation is accepted, review the developer diagrams under `design/` and update the relevant institution-to-consultant chat flow if those diagrams are maintained for this feature.
