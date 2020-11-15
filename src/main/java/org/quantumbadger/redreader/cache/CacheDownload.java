/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.cache;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.quantumbadger.redreader.activities.BugReportActivity;
import org.quantumbadger.redreader.common.PrioritisedCachedThreadPool;
import org.quantumbadger.redreader.common.Priority;
import org.quantumbadger.redreader.common.RRTime;
import org.quantumbadger.redreader.common.TorCommon;
import org.quantumbadger.redreader.http.HTTPBackend;
import org.quantumbadger.redreader.reddit.api.RedditOAuth;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CacheDownload extends PrioritisedCachedThreadPool.Task {

	private static final String TAG = "CacheDownload";

	private final CacheRequest mInitiator;
	private final CacheManager manager;
	private final UUID session;

	private volatile boolean mCancelled = false;
	private static final AtomicBoolean resetUserCredentials = new AtomicBoolean(false);
	private final HTTPBackend.Request mRequest;

	public CacheDownload(
			final CacheRequest initiator,
			final CacheManager manager) {

		this.mInitiator = initiator;

		this.manager = manager;

		if(!initiator.setDownload(this)) {
			mCancelled = true;
		}

		if(initiator.requestSession != null) {
			session = initiator.requestSession;
		} else {
			session = UUID.randomUUID();
		}

		mRequest = HTTPBackend.getBackend().prepareRequest(
				initiator.context,
				new HTTPBackend.RequestDetails(mInitiator.url, mInitiator.postFields));
	}

	public synchronized void cancel() {

		mCancelled = true;

		new Thread() {
			@Override
			public void run() {
				if(mRequest != null) {
					mRequest.cancel();
					mInitiator.notifyFailure(
							CacheRequest.REQUEST_FAILURE_CANCELLED,
							null,
							null,
							"Cancelled");
				}
			}
		}.start();
	}

	public void doDownload() {

		if(mCancelled) {
			return;
		}

		final CacheActivityTracker.ActiveRequest trackerDetails
				= new CacheActivityTracker.ActiveRequest(mInitiator.url.toString());

		CacheActivityTracker.registerRequest(trackerDetails);

		try {
			performDownload(mRequest, trackerDetails);

		} catch(final Throwable t) {
			BugReportActivity.handleGlobalError(mInitiator.context, t);

		} finally {
			CacheActivityTracker.unregisterRequest(trackerDetails);
		}
	}

	public static void resetUserCredentialsOnNextRequest() {
		resetUserCredentials.set(true);
	}

	private void performDownload(
			final HTTPBackend.Request request,
			final CacheActivityTracker.ActiveRequest trackerDetails) {

		if(mInitiator.queueType == CacheRequest.DOWNLOAD_QUEUE_REDDIT_API) {

			if(resetUserCredentials.getAndSet(false)) {
				mInitiator.user.setAccessToken(null);
			}

			RedditOAuth.AccessToken accessToken
					= mInitiator.user.getMostRecentAccessToken();

			if(accessToken == null || accessToken.isExpired()) {

				mInitiator.notifyProgress(true, 0, 0);

				final RedditOAuth.FetchAccessTokenResult result;

				if(mInitiator.user.isAnonymous()) {
					result = RedditOAuth.fetchAnonymousAccessTokenSynchronous(mInitiator.context);

				} else {
					result = RedditOAuth.fetchAccessTokenSynchronous(
							mInitiator.context,
							mInitiator.user);
				}

				if(result.status != RedditOAuth.FetchAccessTokenResultStatus.SUCCESS) {
					mInitiator.notifyFailure(
							CacheRequest.REQUEST_FAILURE_REQUEST,
							result.error.t,
							result.error.httpStatus,
							result.error.title + ": " + result.error.message);
					return;
				}

				accessToken = result.accessToken;
				mInitiator.user.setAccessToken(accessToken);
			}

			request.addHeader("Authorization", "bearer " + accessToken.token);

		}

		if(mInitiator.queueType == CacheRequest.DOWNLOAD_QUEUE_IMGUR_API) {
			request.addHeader("Authorization", "Client-ID c3713d9e7674477");
		}

		mInitiator.notifyDownloadStarted();

		request.executeInThisThread(new HTTPBackend.Listener() {
			@Override
			public void onError(
					final @CacheRequest.RequestFailureType int failureType,
					final Throwable exception,
					final Integer httpStatus) {
				if(mInitiator.queueType == CacheRequest.DOWNLOAD_QUEUE_REDDIT_API
						&& TorCommon.isTorEnabled()) {
					HTTPBackend.getBackend().recreateHttpBackend();
					resetUserCredentialsOnNextRequest();
				}

				mInitiator.notifyFailure(failureType, exception, httpStatus, "");
			}

			@Override
			public void onSuccess(
					final String mimetype,
					final Long bodyBytes,
					final InputStream is) {

				final ArrayList<CacheDataStreamChunkConsumer> consumers = new ArrayList<>();
				@Nullable CacheManager.WritableCacheFile writableCacheFile = null;

				if(mInitiator.cache) {
					try {
						writableCacheFile
								= manager.openNewCacheFile(mInitiator, session, mimetype);

						consumers.add(writableCacheFile);

					} catch(final IOException e) {

						Log.e(TAG, "Exception opening cache file for write", e);

						final int failureType;

						if(manager.getPreferredCacheLocation().exists()) {
							failureType = CacheRequest.REQUEST_FAILURE_STORAGE;
						} else {
							failureType
									= CacheRequest.REQUEST_FAILURE_CACHE_DIR_DOES_NOT_EXIST;
						}

						mInitiator.notifyFailure(
								failureType,
								e,
								null,
								"Could not access the local cache");

						return;
					}
				}

				{
					final CacheDataStreamChunkConsumer consumer
							= mInitiator.notifyDataStreamAvailable();

					if(consumer != null) {
						consumers.add(consumer);
					}
				}

				try {
					final byte[] buf = new byte[128 * 1024];

					int bytesRead;
					long totalBytesRead = 0;

					while((bytesRead = is.read(buf)) > 0) {

						totalBytesRead += bytesRead;

						for(int i = 0; i < consumers.size(); i++) {
							consumers.get(i).onDataStreamChunk(buf, 0, bytesRead);
						}

						if(bodyBytes != null) {
							mInitiator.notifyProgress(
									false,
									totalBytesRead,
									bodyBytes);

							trackerDetails.progressPercent.set(
									(int)((100 * totalBytesRead) / bodyBytes));
						}
					}

					for(int i = 0; i < consumers.size(); i++) {
						consumers.get(i).onDataStreamSuccess();
					}

					mInitiator.notifySuccess(
							writableCacheFile == null
									? null
									: writableCacheFile.getReadableCacheFile(),
							RRTime.utcCurrentTimeMillis(),
							session,
							false,
							mimetype);

				} catch(final IOException e) {

					if(e.getMessage() != null && e.getMessage().contains("ENOSPC")) {
						mInitiator.notifyFailure(
								CacheRequest.REQUEST_FAILURE_STORAGE,
								e,
								null,
								"Out of disk space");

					} else {
						e.printStackTrace();
						mInitiator.notifyFailure(
								CacheRequest.REQUEST_FAILURE_CONNECTION,
								e,
								null,
								"The connection was interrupted");
					}

				} catch(final Throwable t) {
					t.printStackTrace();
					mInitiator.notifyFailure(
							CacheRequest.REQUEST_FAILURE_CONNECTION,
							t,
							null,
							"The connection was interrupted");
				}
			}
		});
	}

	@NonNull
	@Override
	public Priority getPriority() {
		return mInitiator.priority;
	}

	@Override
	public void run() {
		doDownload();
	}
}
