package uk.co.senab.photup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import uk.co.senab.photup.events.PhotoSelectionAddedEvent;
import uk.co.senab.photup.events.PhotoSelectionRemovedEvent;
import uk.co.senab.photup.events.UploadsModifiedEvent;
import uk.co.senab.photup.model.Account;
import uk.co.senab.photup.model.FbUser;
import uk.co.senab.photup.model.PhotoUpload;
import uk.co.senab.photup.model.Place;
import uk.co.senab.photup.model.UploadQuality;
import uk.co.senab.photup.util.PhotoUploadDatabaseHelper;
import android.content.Context;
import de.greenrobot.event.EventBus;

public class PhotoUploadController {

	public static PhotoUploadController getFromContext(Context context) {
		return PhotupApplication.getApplication(context).getPhotoUploadController();
	}

	private final Context mContext;
	private final ArrayList<PhotoUpload> mSelectedPhotoList;
	private final ArrayList<PhotoUpload> mUploadingList;

	PhotoUploadController(Context context) {
		mContext = context;
		mSelectedPhotoList = new ArrayList<PhotoUpload>();
		mUploadingList = new ArrayList<PhotoUpload>();

		populateFromDatabase();
	}

	public boolean addSelection(final PhotoUpload selection) {
		if (!isSelected(selection)) {

			// Remove it from Upload list if it's there
			if (isOnUploadList(selection)) {
				removeUpload(selection);
			}

			selection.setUploadState(PhotoUpload.STATE_SELECTED);
			mSelectedPhotoList.add(selection);

			// Save to Database
			if (Flags.ENABLE_DB_PERSISTENCE) {
				PhotoUploadDatabaseHelper.saveToDatabase(mContext, selection);
			}

			postEvent(new PhotoSelectionAddedEvent(selection));
			return true;
		}

		return false;
	}

	public void addSelections(List<PhotoUpload> selections) {
		final HashSet<PhotoUpload> currentSelectionsSet = new HashSet<PhotoUpload>(mSelectedPhotoList);
		final HashSet<PhotoUpload> currentUploadSet = new HashSet<PhotoUpload>(mUploadingList);
		boolean listModified = false;

		for (final PhotoUpload selection : selections) {
			if (!currentSelectionsSet.contains(selection)) {

				// Remove it from Upload list if it's there
				if (currentUploadSet.contains(selection)) {
					removeUpload(selection);
				}

				selection.setUploadState(PhotoUpload.STATE_SELECTED);
				mSelectedPhotoList.add(selection);
				listModified = true;
			}
		}

		if (listModified) {
			// Save to Database
			if (Flags.ENABLE_DB_PERSISTENCE) {
				PhotoUploadDatabaseHelper.saveToDatabase(mContext, mSelectedPhotoList, true);
			}

			postEvent(new PhotoSelectionAddedEvent(selections));
		}
	}

	public boolean addUpload(PhotoUpload selection) {
		if (null != selection && !mUploadingList.contains(selection)) {
			selection.setUploadState(PhotoUpload.STATE_UPLOAD_WAITING);

			// Save to Database
			if (Flags.ENABLE_DB_PERSISTENCE) {
				PhotoUploadDatabaseHelper.saveToDatabase(mContext, selection);
			}

			mUploadingList.add(selection);
			mSelectedPhotoList.remove(selection);

			postEvent(new UploadsModifiedEvent());
			return true;
		}
		return false;
	}

	public void addUploadsFromSelected(final Account account, final String targetId, final UploadQuality quality,
			final Place place) {

		for (PhotoUpload upload : mSelectedPhotoList) {
			upload.setUploadParams(account, targetId, quality);
			upload.setUploadState(PhotoUpload.STATE_UPLOAD_WAITING);

			if (null != place) {
				upload.setPlace(place);
			}
		}

		// Update Database
		if (Flags.ENABLE_DB_PERSISTENCE) {
			PhotoUploadDatabaseHelper.saveToDatabase(mContext, mSelectedPhotoList, true);
		}

		ArrayList<PhotoUpload> eventResult = new ArrayList<PhotoUpload>(mSelectedPhotoList);

		mUploadingList.addAll(mSelectedPhotoList);
		mSelectedPhotoList.clear();

		postEvent(new PhotoSelectionRemovedEvent(eventResult));
		postEvent(new UploadsModifiedEvent());
	}

	public void clearSelected() {
		if (!mSelectedPhotoList.isEmpty()) {

			// Delete from Database
			if (Flags.ENABLE_DB_PERSISTENCE) {
				PhotoUploadDatabaseHelper.deleteAllSelected(mContext);
			}

			// Reset States (as may still be in cache)
			for (PhotoUpload upload : mSelectedPhotoList) {
				upload.setUploadState(PhotoUpload.STATE_NONE);
			}

			ArrayList<PhotoUpload> eventResult = new ArrayList<PhotoUpload>(mSelectedPhotoList);

			// Clear from memory
			mSelectedPhotoList.clear();

			postEvent(new PhotoSelectionRemovedEvent(eventResult));
		}
	}

	public int getActiveUploadsCount() {
		int count = 0;
		for (PhotoUpload upload : mUploadingList) {
			if (upload.getUploadState() != PhotoUpload.STATE_UPLOAD_COMPLETED) {
				count++;
			}
		}
		return count;
	}

	public PhotoUpload getNextUpload() {
		for (PhotoUpload selection : mUploadingList) {
			if (selection.getUploadState() == PhotoUpload.STATE_UPLOAD_WAITING) {
				return selection;
			}
		}
		return null;
	}

	public List<PhotoUpload> getSelected() {
		return new ArrayList<PhotoUpload>(mSelectedPhotoList);
	}

	public int getSelectedCount() {
		return mSelectedPhotoList.size();
	}

	public List<PhotoUpload> getUploadingUploads() {
		return new ArrayList<PhotoUpload>(mUploadingList);
	}

	public int getUploadsCount() {
		return mUploadingList.size();
	}

	public boolean hasSelections() {
		return !mSelectedPhotoList.isEmpty();
	}

	public boolean hasSelectionsWithPlace() {
		for (PhotoUpload selection : mSelectedPhotoList) {
			if (selection.hasPlace()) {
				return true;
			}
		}
		return false;
	}

	public boolean hasUploads() {
		return !mUploadingList.isEmpty();
	}

	public boolean hasWaitingUploads() {
		for (PhotoUpload upload : mUploadingList) {
			if (upload.getUploadState() == PhotoUpload.STATE_UPLOAD_WAITING) {
				return true;
			}
		}
		return false;
	}

	public boolean isSelected(PhotoUpload selection) {
		return mSelectedPhotoList.contains(selection);
	}

	public boolean isOnUploadList(PhotoUpload selection) {
		return mUploadingList.contains(selection);
	}

	public boolean moveFailedToSelected() {
		boolean result = false;

		final Iterator<PhotoUpload> iterator = mUploadingList.iterator();
		PhotoUpload upload;

		while (iterator.hasNext()) {
			upload = iterator.next();

			if (upload.getUploadState() == PhotoUpload.STATE_UPLOAD_ERROR) {
				// Reset State and add to selection list
				upload.setUploadState(PhotoUpload.STATE_SELECTED);
				addSelection(upload);

				// Remove from Uploading list
				iterator.remove();
				result = true;
			}
		}

		// Update Database, but don't force update
		if (Flags.ENABLE_DB_PERSISTENCE) {
			PhotoUploadDatabaseHelper.saveToDatabase(mContext, mSelectedPhotoList, false);
		}

		// The Uploading List has been changed, send event
		if (result) {
			postEvent(new UploadsModifiedEvent());
		}

		return result;
	}

	public boolean removeSelection(final PhotoUpload selection) {
		if (mSelectedPhotoList.remove(selection)) {
			// Delete from Database
			if (Flags.ENABLE_DB_PERSISTENCE) {
				PhotoUploadDatabaseHelper.deleteFromDatabase(mContext, selection);
			}

			// Reset State (as may still be in cache)
			selection.setUploadState(PhotoUpload.STATE_NONE);

			postEvent(new PhotoSelectionRemovedEvent(selection));
			return true;
		}

		return false;
	}

	public void removeUpload(final PhotoUpload selection) {
		if (mUploadingList.remove(selection)) {

			// Delete from Database
			if (Flags.ENABLE_DB_PERSISTENCE) {
				PhotoUploadDatabaseHelper.deleteFromDatabase(mContext, selection);
			}

			// Reset State (as may still be in cache)
			selection.setUploadState(PhotoUpload.STATE_NONE);

			postEvent(new UploadsModifiedEvent());
		}
	}

	public void reset() {
		// Clear the cache
		PhotoUpload.clearCache();

		// Clear the internal lists
		mSelectedPhotoList.clear();
		mUploadingList.clear();

		// Finally delete the database
		mContext.deleteDatabase(DatabaseHelper.DATABASE_NAME);
	}

	public void updateDatabase() {
		if (Flags.ENABLE_DB_PERSISTENCE) {
			PhotoUploadDatabaseHelper.saveToDatabase(mContext, mSelectedPhotoList, false);
			PhotoUploadDatabaseHelper.saveToDatabase(mContext, mUploadingList, false);
		}
	}

	void populateDatabaseItemsFromAccounts(HashMap<String, Account> accounts) {
		if (!mSelectedPhotoList.isEmpty()) {
			for (PhotoUpload upload : mSelectedPhotoList) {
				upload.populateFromAccounts(accounts);
			}
		}
		if (!mUploadingList.isEmpty()) {
			for (PhotoUpload upload : mUploadingList) {
				upload.populateFromAccounts(accounts);
			}
		}
	}

	void populateDatabaseItemsFromFriends(HashMap<String, FbUser> friends) {
		if (!mSelectedPhotoList.isEmpty()) {
			for (PhotoUpload upload : mSelectedPhotoList) {
				upload.populateFromFriends(friends);
			}
		}
		if (!mUploadingList.isEmpty()) {
			for (PhotoUpload upload : mUploadingList) {
				upload.populateFromFriends(friends);
			}
		}
	}

	void populateFromDatabase() {
		if (Flags.ENABLE_DB_PERSISTENCE) {
			final List<PhotoUpload> selectedFromDb = PhotoUploadDatabaseHelper.getSelected(mContext);
			if (null != selectedFromDb) {
				// Should do contains() on each item really...
				mSelectedPhotoList.addAll(selectedFromDb);
				PhotoUpload.populateCache(selectedFromDb);
			}

			final List<PhotoUpload> uploadsFromDb = PhotoUploadDatabaseHelper.getUploads(mContext);
			if (null != uploadsFromDb) {
				// Should do contains() on each item really...
				mUploadingList.addAll(uploadsFromDb);
				PhotoUpload.populateCache(uploadsFromDb);
			}
		}
	}

	private void postEvent(Object event) {
		EventBus.getDefault().post(event);
	}

}
