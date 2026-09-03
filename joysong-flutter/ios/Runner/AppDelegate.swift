import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, UIDocumentPickerDelegate {
  private var pendingFileResult: FlutterResult?

  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    GeneratedPluginRegistrant.register(with: self)
    if let controller = window?.rootViewController as? FlutterViewController {
      let channel = FlutterMethodChannel(
        name: "com.joysong.app/file_picker",
        binaryMessenger: controller.binaryMessenger
      )
      channel.setMethodCallHandler { [weak self] call, result in
        guard call.method == "pickFile" else {
          result(FlutterMethodNotImplemented)
          return
        }
        self?.presentFilePicker(call: call, result: result)
      }
    }
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  private func presentFilePicker(call: FlutterMethodCall, result: @escaping FlutterResult) {
    guard pendingFileResult == nil else {
      result(FlutterError(
        code: "PICK_IN_PROGRESS",
        message: "A file picker is already open.",
        details: nil
      ))
      return
    }
    let arguments = call.arguments as? [String: Any]
    let extensions = arguments?["extensions"] as? [String] ?? []
    let documentTypes = extensions.compactMap(documentType(for:))
    let picker = UIDocumentPickerViewController(
      documentTypes: documentTypes.isEmpty ? ["public.data"] : documentTypes,
      in: .import
    )
    picker.delegate = self
    picker.allowsMultipleSelection = false
    pendingFileResult = result
    window?.rootViewController?.present(picker, animated: true)
  }

  func documentPicker(
    _ controller: UIDocumentPickerViewController,
    didPickDocumentsAt urls: [URL]
  ) {
    guard let result = pendingFileResult else { return }
    pendingFileResult = nil
    guard let url = urls.first else {
      result(nil)
      return
    }
    let granted = url.startAccessingSecurityScopedResource()
    defer {
      if granted { url.stopAccessingSecurityScopedResource() }
    }
    do {
      let values = try url.resourceValues(forKeys: [.fileSizeKey, .nameKey])
      if let size = values.fileSize, size > 10 * 1024 * 1024 {
        result(FlutterError(
          code: "FILE_TOO_LARGE",
          message: "The selected file exceeds 10 MB.",
          details: nil
        ))
        return
      }
      let data = try Data(contentsOf: url, options: [.mappedIfSafe])
      guard !data.isEmpty, data.count <= 10 * 1024 * 1024 else {
        result(FlutterError(
          code: "FILE_TOO_LARGE",
          message: "The selected file is empty or exceeds 10 MB.",
          details: nil
        ))
        return
      }
      let fileName = values.name ?? url.lastPathComponent
      result([
        "bytes": FlutterStandardTypedData(bytes: data),
        "fileName": fileName,
        "mimeType": mimeType(for: url.pathExtension),
      ])
    } catch {
      result(FlutterError(
        code: "FILE_UNREADABLE",
        message: "Unable to read the selected file.",
        details: nil
      ))
    }
  }

  func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
    pendingFileResult?(nil)
    pendingFileResult = nil
  }

  private func documentType(for fileExtension: String) -> String? {
    switch fileExtension.lowercased() {
    case "jpg", "jpeg": return "public.jpeg"
    case "png": return "public.png"
    case "webp": return "org.webmproject.webp"
    case "pdf": return "com.adobe.pdf"
    default: return nil
    }
  }

  private func mimeType(for fileExtension: String) -> String {
    switch fileExtension.lowercased() {
    case "jpg", "jpeg": return "image/jpeg"
    case "png": return "image/png"
    case "webp": return "image/webp"
    case "pdf": return "application/pdf"
    default: return "application/octet-stream"
    }
  }
}
