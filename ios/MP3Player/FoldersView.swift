import SwiftUI
import UniformTypeIdentifiers

/// The folders songs come from: the app's own Documents folder (and its
/// subfolders) plus folders linked from the Files app, which are read in place.
struct FoldersView: View {
    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var settings: SettingsStore
    @State private var showPicker = false
    @State private var linkFailed = false

    var body: some View {
        let groups = library.folderGroups()

        NavigationStack {
            List {
                Section {
                    ForEach(groups) { group in
                        NavigationLink(value: group) {
                            HStack(spacing: 12) {
                                Image(systemName: "folder.fill")
                                    .font(.title3)
                                    .foregroundStyle(Color.accentColor)
                                    .frame(width: 32)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(group.name).lineLimit(1)
                                    Text(group.detail.isEmpty
                                         ? songCount(group.songCount)
                                         : "\(group.detail) · \(songCount(group.songCount))")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                        .truncationMode(.head)
                                }
                            }
                        }
                    }
                } header: {
                    Text("Folders with Music")
                }

                Section {
                    LabeledContent("On This iPhone", value: "Always included")
                    ForEach(settings.settings.linkedFolders) { folder in
                        Label(folder.name, systemImage: "link")
                    }
                    .onDelete { offsets in
                        let ids = offsets.map { settings.settings.linkedFolders[$0].id }
                        Task { for id in ids { await library.unlinkFolder(id) } }
                    }
                    Button {
                        showPicker = true
                    } label: {
                        Label("Link a Folder…", systemImage: "folder.badge.plus")
                    }
                } header: {
                    Text("Sources")
                } footer: {
                    Text("Linked folders (iCloud Drive, On My iPhone, USB drives…) are played where they are, "
                         + "subfolders included; nothing is copied. Swipe to unlink. Songs you import with + "
                         + "and files added through Finder go into On This iPhone.")
                }
            }
            .overlay {
                if groups.isEmpty && !library.isScanning {
                    ContentUnavailableView("No Folders", systemImage: "folder",
                                           description: Text("Import songs or link a folder."))
                        .allowsHitTesting(false)
                        .padding(.bottom, 200)
                }
            }
            .navigationTitle("Folders")
            .navigationDestination(for: FolderGroup.self) { group in
                SongCollectionView(title: group.name, sortByAlbum: false) { $0.folderKey == group.key }
            }
            .refreshable { await library.reload() }
            .fileImporter(isPresented: $showPicker, allowedContentTypes: [.folder]) { result in
                guard case .success(let url) = result else { return }
                Task {
                    if !(await library.linkFolder(url)) { linkFailed = true }
                }
            }
            .alert("Couldn't Link Folder", isPresented: $linkFailed) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("The app wasn't given access to that folder.")
            }
        }
    }

    private func songCount(_ n: Int) -> String {
        "\(n) song\(n == 1 ? "" : "s")"
    }
}
