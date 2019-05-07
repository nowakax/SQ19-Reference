package org.jabref.gui.mergeentries;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.jabref.Globals;
import org.jabref.gui.BasePanel;
import org.jabref.gui.DialogService;
import org.jabref.gui.undo.NamedCompound;
import org.jabref.gui.undo.UndoableChangeType;
import org.jabref.gui.undo.UndoableFieldChange;
import org.jabref.gui.util.BackgroundTask;
import org.jabref.gui.util.TaskExecutor;
import org.jabref.logic.importer.EntryBasedFetcher;
import org.jabref.logic.importer.IdBasedFetcher;
import org.jabref.logic.importer.WebFetcher;
import org.jabref.logic.importer.WebFetchers;
import org.jabref.logic.l10n.Localization;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.FieldName;
import org.jabref.model.entry.InternalBibtexFields;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class for fetching and merging bibliographic information
 */
public class FetchAndMergeEntry {

    // A list of all field which are supported
    public static List<String> SUPPORTED_FIELDS = Arrays.asList(FieldName.DOI, FieldName.EPRINT, FieldName.ISBN);
    private static final Logger LOGGER = LoggerFactory.getLogger(FetchAndMergeEntry.class);
    private final BasePanel panel;
    private final DialogService dialogService;
    private final TaskExecutor taskExecutor;

    public FetchAndMergeEntry(BasePanel panel, TaskExecutor taskExecutor) {
        this.dialogService = panel.frame().getDialogService();
        this.panel = panel;
        this.taskExecutor = taskExecutor;
    }

    public void fetchAndMerge(BibEntry entry) {
        fetchAndMerge(entry, SUPPORTED_FIELDS);
    }

    public void fetchAndMerge(BibEntry entry, String field) {
        fetchAndMerge(entry, Collections.singletonList(field));
    }

    public void fetchAndMerge(BibEntry entry, List<String> fields) {
        for (String field : fields) {
            Optional<String> fieldContent = entry.getField(field);
            if (fieldContent.isPresent()) {
                Optional<IdBasedFetcher> fetcher = WebFetchers.getIdBasedFetcherForField(field, Globals.prefs.getImportFormatPreferences());
                if (fetcher.isPresent()) {
                    BackgroundTask.wrap(() -> fetcher.get().performSearchById(fieldContent.get()))
                                  .onSuccess(fetchedEntry -> {
                                      String type = FieldName.getDisplayName(field);
                                      if (fetchedEntry.isPresent()) {
                                          showMergeDialog(entry, fetchedEntry.get(), fetcher.get());
                                      } else {
                                          dialogService.notify(Localization.lang("Cannot get info based on given %0: %1", type, fieldContent.get()));
                                      }
                                  })
                                  .onFailure(exception -> {
                                      LOGGER.error("Error while fetching bibliographic information", exception);
                                      dialogService.showErrorDialogAndWait(exception);
                                  })
                                  .executeWith(Globals.TASK_EXECUTOR);
                }
            } else {
                dialogService.notify(Localization.lang("No %0 found", FieldName.getDisplayName(field)));
            }
        }
    }

    private void showMergeDialog(BibEntry originalEntry, BibEntry fetchedEntry, WebFetcher fetcher) {
        MergeEntriesDialog dialog = new MergeEntriesDialog(originalEntry, fetchedEntry, panel.getBibDatabaseContext().getMode());
        dialog.setTitle(Localization.lang("Merge entry with %0 information", fetcher.getName()));
        dialog.setLeftHeaderText(Localization.lang("Original entry"));
        dialog.setRightHeaderText(Localization.lang("Entry from %0", fetcher.getName()));
        Optional<BibEntry> mergedEntry = dialog.showAndWait();
        if (mergedEntry.isPresent()) {
            NamedCompound ce = new NamedCompound(Localization.lang("Merge entry with %0 information", fetcher.getName()));

            // Updated the original entry with the new fields
            Set<String> jointFields = new TreeSet<>(mergedEntry.get().getFieldNames());
            Set<String> originalFields = new TreeSet<>(originalEntry.getFieldNames());
            boolean edited = false;

            // entry type
            String oldType = originalEntry.getType();
            String newType = mergedEntry.get().getType();

            if (!oldType.equalsIgnoreCase(newType)) {
                originalEntry.setType(newType);
                ce.addEdit(new UndoableChangeType(originalEntry, oldType, newType));
                edited = true;
            }

            // fields
            for (String field : jointFields) {
                Optional<String> originalString = originalEntry.getField(field);
                Optional<String> mergedString = mergedEntry.get().getField(field);
                if (!originalString.isPresent() || !originalString.equals(mergedString)) {
                    originalEntry.setField(field, mergedString.get()); // mergedString always present
                    ce.addEdit(new UndoableFieldChange(originalEntry, field, originalString.orElse(null),
                            mergedString.get()));
                    edited = true;
                }
            }

            // Remove fields which are not in the merged entry, unless they are internal fields
            for (String field : originalFields) {
                if (!jointFields.contains(field) && !InternalBibtexFields.isInternalField(field)) {
                    Optional<String> originalString = originalEntry.getField(field);
                    originalEntry.clearField(field);
                    ce.addEdit(new UndoableFieldChange(originalEntry, field, originalString.get(), null)); // originalString always present
                    edited = true;
                }
            }

            if (edited) {
                ce.end();
                panel.getUndoManager().addEdit(ce);
                dialogService.notify(Localization.lang("Updated entry with info from %0", fetcher.getName()));
            } else {
                dialogService.notify(Localization.lang("No information added"));
            }
        } else {
            dialogService.notify(Localization.lang("Canceled merging entries"));
        }
    }

    public void fetchAndMerge(BibEntry entry, EntryBasedFetcher fetcher) {
        BackgroundTask.wrap(() -> fetcher.performSearch(entry).stream().findFirst())
                      .onSuccess(fetchedEntry -> {
                          if (fetchedEntry.isPresent()) {
                              showMergeDialog(entry, fetchedEntry.get(), fetcher);
                          } else {
                              dialogService.notify(Localization.lang("Could not find any bibliographic information."));
                          }
                      })
                      .onFailure(exception -> {
                          LOGGER.error("Error while fetching entry with " + fetcher.getName(), exception);
                          dialogService.showErrorDialogAndWait(Localization.lang("Error while fetching from %0", fetcher.getName()), exception);
                      })
                      .executeWith(taskExecutor);
    }
}
