/*
 * Copyright (c) 2011 Roberto Tyley
 * This file is part of 'lazy-drawables'.
 *
 * 'lazy-drawables' is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 'lazy-drawables' is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details
 *
 * You should have received a copy of the GNU General Public License
 * along with 'lazy-drawables'.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.madgag.android.lazydrawables;

import static java.util.Collections.synchronizedMap;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.Log;

import com.google.common.base.Function;
import com.google.common.collect.MapMaker;

public class ImageSession<K, ImageResourceType> {

	private final static String TAG = "IS";

	private final ImageProcessor<ImageResourceType> imageProcessor;
	private final ImageResourceDownloader<K, ImageResourceType> downloader;
	private final ImageResourceStore<K, ImageResourceType> imageResourceStore;
	private final ConcurrentMap<K, Drawable> memoryImageCache = new ConcurrentHashMap<K, Drawable>();
	private final Drawable downloadingDrawable;

	private final ConcurrentMap<K, AsyncImageDownloaderTask> asyncDownloaders = new MapMaker()
			.makeComputingMap(new Function<K, AsyncImageDownloaderTask>() {
				public AsyncImageDownloaderTask apply(K key) {
					AsyncImageDownloaderTask asyncImageDownloaderTask = new AsyncImageDownloaderTask(
							key);
					asyncImageDownloaderTask.execute();
					return asyncImageDownloaderTask;
				}
			});

	public ImageSession(ImageProcessor<ImageResourceType> imageProcessor,
			ImageResourceDownloader<K, ImageResourceType> downloader,
			ImageResourceStore<K, ImageResourceType> imageResourceStore,
			Drawable downloadingDrawable) {
		this.imageProcessor = imageProcessor;
		this.downloader = downloader;
		this.imageResourceStore = imageResourceStore;
		this.downloadingDrawable = downloadingDrawable;
	}

	public Drawable get(K key) {
		Drawable memoryCachedDrawable = memoryImageCache.get(key);
		if (memoryCachedDrawable != null) {
			return memoryCachedDrawable;
		}

		ImageResourceType storedResource = imageResourceStore.get(key);
		if (storedResource != null) {
			return convertAndCache(key, storedResource);
		}

		//Drawable downloadingDrawable2 = downloadingDrawable();
		AsyncLoadDrawable asyncLoadDrawable = new AsyncLoadDrawable();
		asyncLoadDrawable.setDelegate(downloadingDrawable);

		asyncDownloaders.get(key).register(asyncLoadDrawable);
		return asyncLoadDrawable;
	}
//
//	private Drawable downloadingDrawable() {
//		Bitmap bm = Bitmap.createBitmap(56, 56, Bitmap.Config.ARGB_8888);
//		Canvas c = new Canvas(bm);
//		Paint paint = new Paint();
//		paint.setColor(0x88888888);
//		c.drawCircle(0, 0, 25, paint);
//		
//		return new BitmapDrawable(bm);
//	}

	private Drawable convertAndCache(K key, ImageResourceType storedResource) {
		Drawable drawable = imageProcessor.convert(storedResource);
		memoryImageCache.put(key, drawable);
		return drawable;
	}

	class AsyncImageDownloaderTask extends AsyncTask<Void, Void, Drawable> {

		private final K key;
		private final Map<AsyncLoadDrawable, Boolean> drawablesToUpdate = synchronizedMap(new WeakHashMap<AsyncLoadDrawable, Boolean>());

		public AsyncImageDownloaderTask(K key) {
			this.key = key;
		}

		public void register(AsyncLoadDrawable asyncLoadDrawable) {
			drawablesToUpdate.put(asyncLoadDrawable, Boolean.TRUE);
		}

		@Override
		protected Drawable doInBackground(Void... params) {
			Log.d(TAG, "Totally Background loading " + key);
			try {
				ImageResourceType imageResource = null;
				try {
					imageResource = downloader.get(key);
				} catch (Exception e) {
					Log.e(TAG, "Failed loading " + key, e);
				}
				Log.d(TAG, "Received " + key+" : "+imageResource);
				if (imageResource != null) {
					imageResourceStore.put(key, imageResource);
					Log.d(TAG, "Stored " + key + " " + imageResource);
					return convertAndCache(key, imageResource);
				} else {
					return null;
				}
			} finally {
				asyncDownloaders.remove(this);
			}
		}

		protected void onPostExecute(Drawable result) {
			if (result==null) {
				return;
			}
			Log.d(TAG, "Updating " + drawablesToUpdate.size() + " drawables for " + key + " " + result);
			for (AsyncLoadDrawable asyncLoadDrawable : drawablesToUpdate.keySet()) {
				asyncLoadDrawable.onLoad(result);
			}
		};

	}

}
