package net.nightwhistler.pageturner.activity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import javax.annotation.Nullable;

import net.nightwhistler.htmlspanner.HtmlSpanner;
import net.nightwhistler.nucular.atom.AtomConstants;
import net.nightwhistler.nucular.atom.Entry;
import net.nightwhistler.nucular.atom.Feed;
import net.nightwhistler.nucular.atom.Link;
import net.nightwhistler.nucular.parser.Nucular;
import net.nightwhistler.nucular.parser.opensearch.SearchDescription;
import net.nightwhistler.pageturner.Configuration;
import net.nightwhistler.pageturner.CustomOPDSSite;
import net.nightwhistler.pageturner.R;
import net.nightwhistler.pageturner.catalog.CatalogListAdapter;
import net.nightwhistler.pageturner.library.LibraryService;
import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.epub.EpubReader;

import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import roboguice.inject.InjectView;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.github.rtyley.android.sherlock.roboguice.fragment.RoboSherlockFragment;
import com.google.inject.Inject;

public class CatalogFragment extends RoboSherlockFragment implements
		OnItemClickListener {
	
    private static final String STATE_NAV_ARRAY_KEY = "nav_array";
    
    private static final int ABBREV_TEXT_LEN = 150;
	private static final String CUSTOM_SITES_ID = "IdCustomSites";
	
	private static final int MAX_THUMBNAIL_WIDTH = 85;
	
    private String baseURL = "";
	private String user = "";
	private String password = "";

	private CatalogListAdapter adapter;

	private ProgressDialog waitDialog;
	private ProgressDialog downloadDialog;

	private static final Logger LOG = LoggerFactory
			.getLogger("CatalogFragment");

	private Stack<String> navStack = new Stack<String>();

	@InjectView(R.id.catalogList)
	@Nullable
	private ListView catalogList;

	@Inject
	private Configuration config;
	
	@Inject
	private LibraryService libraryService;
	
	private LinkListener linkListener;

	private static interface LinkListener {
		void linkUpdated();
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (savedInstanceState != null) {
			List<String> navList = savedInstanceState.getStringArrayList(STATE_NAV_ARRAY_KEY);
			if (navList != null && navList.size() > 0) {
				navStack.addAll(navList);
			}
		}
		this.adapter = new DownloadingCatalogAdapter();
		this.baseURL = config.getBaseOPDSFeed();
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_catalog, container, false);
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		setHasOptionsMenu(true);
		catalogList.setAdapter(adapter);
		catalogList.setOnItemClickListener(this);
		
		this.waitDialog = new ProgressDialog(getActivity());
		this.waitDialog.setOwnerActivity(getActivity());

		this.downloadDialog = new ProgressDialog(getActivity());

		this.downloadDialog.setIndeterminate(false);
		this.downloadDialog.setMax(100);
		this.downloadDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
		this.downloadDialog.setCancelable(true);
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		Intent intent = getActivity().getIntent();
		
		if (!navStack.empty()) {
			new LoadOPDSTask().execute(navStack.peek());
		} else {
			Uri uri = intent.getData();

			if (uri != null && uri.toString().startsWith("epub://")) {
				String downloadUrl = uri.toString().replace("epub://", "http://");
				new DownloadFileTask(false).execute(downloadUrl);
			} else {
				new LoadOPDSTask().execute(baseURL);
			}
		}
	}

    @Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if (!navStack.empty()) {
			ArrayList<String> navList = new ArrayList<String>(navStack);
			outState.putStringArrayList(STATE_NAV_ARRAY_KEY, navList);
		}
	}

	public void onSearchClick() {

		AlertDialog.Builder alert = new AlertDialog.Builder(getActivity());

		alert.setTitle(R.string.search_books);
		alert.setMessage(R.string.enter_query);

		// Set an EditText view to get user input
		final EditText input = new EditText(getActivity());
		alert.setView(input);

		alert.setPositiveButton(android.R.string.search_go,
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						CharSequence value = input.getText();
						if (value != null && value.length() > 0) {
							String searchString = URLEncoder.encode(value
									.toString());
							String linkUrl = adapter.getFeed().getSearchLink()
									.getHref();

							linkUrl = linkUrl.replace("{searchTerms}",
									searchString);

							loadURL(linkUrl);
						}
					}
				});

		alert.setNegativeButton(android.R.string.cancel,
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						// Canceled.
					}
				});

		alert.show();
	}

	@Override
	public void onItemClick(AdapterView<?> list, View arg1, int position,
			long arg3) {

		Entry entry = adapter.getItem(position);
		
		if ( entry.getId() != null && entry.getId().equals(CUSTOM_SITES_ID) ) {			
			loadCustomSiteFeed();
		} else if (entry.getAtomLink() != null) {
			String href = entry.getAtomLink().getHref();
			loadURL(entry, href);
		} else {
			loadFakeFeed(entry);
		}
	}

	private boolean isLeafEntry(Feed feed) {
		
		/**
		 * A feed is a leaf if it isn't our custom
		 * sites feed and only has 1 entry.
		 */
		
		return feed.getEntries().size() == 1
				&& ( feed.getId() == null
				|| ! feed.getId().equals(CUSTOM_SITES_ID));
	}
	
	private void loadCustomSiteFeed() {
		
		List<CustomOPDSSite> sites = config.getCustomOPDSSites();
		
		if ( sites.isEmpty() ) {
			Toast.makeText(getActivity(), R.string.no_custom_sites, Toast.LENGTH_LONG).show();
			return;
		}
		
		navStack.add(CUSTOM_SITES_ID);
		
		Feed customSites = new Feed();
		customSites.setTitle(getString(R.string.custom_site));

		
		for ( CustomOPDSSite site: sites ) {
			Entry entry = new Entry();
			entry.setTitle(site.getName());
			entry.setSummary(site.getDescription());
			
			Link link = new Link(site.getUrl(), AtomConstants.TYPE_ATOM, AtomConstants.REL_BUY);
			entry.addLink(link);
			
			customSites.addEntry(entry);
		}
		
		customSites.setId(CUSTOM_SITES_ID);
		
		setNewFeed(customSites);
		
	}

	private void loadFakeFeed(Entry entry) {
		String base = baseURL;

		if (!navStack.isEmpty()) {
			base = navStack.peek();
		}

		navStack.push(base);
		new LoadFakeFeedTask(entry).execute(base);
	}

	private void loadURL(String url) {
		loadURL(null, url);
	}

	private void loadURL(Entry entry, String url) {

		String base = baseURL;

		if (!navStack.isEmpty()) {
			base = navStack.peek();
		}

		try {
			String target = url;
			
			if ( base != null && ! base.equals(CUSTOM_SITES_ID)) {
				target = new URL(new URL(base), url).toString();
			}
			
			this.baseURL = target;
			LOG.info("Loading " + target);

			navStack.push(target);
			new LoadOPDSTask(entry).execute(target);
		} catch (MalformedURLException u) {
			LOG.error("Malformed URL:", u);
		}
	}	

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {		
		getSherlockActivity().getSupportActionBar().setHomeButtonEnabled(true);		
		inflater.inflate(R.menu.catalog_menu, menu);		
	}
	
	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		Feed feed = adapter.getFeed();
		
		if ( feed == null ) {
			return;
		}
		
		boolean nextEnabled = feed.getNextLink() != null;
		boolean prevEnabled = feed.getPreviousLink() != null || navStack.size() > 0;
		boolean searchEnabled = feed.getSearchLink() != null;
		
		for ( int i=0; i < menu.size(); i++ ) {
			MenuItem item = menu.getItem(i);
			
			boolean enabled = false;
			
			switch (item.getItemId()) {
			case R.id.left:
				enabled = prevEnabled;
				break;
			case R.id.right:
				enabled = nextEnabled;
				break;
			case R.id.search:
				enabled = searchEnabled;
				break;			
			default:
				enabled = true;
			}			
			
			item.setEnabled(enabled);
			item.setVisible(enabled);			
		}
	}
	

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		
		
		switch (item.getItemId()) {
		case android.R.id.home:
			navStack.clear();
			this.baseURL = config.getBaseOPDSFeed();
			new LoadOPDSTask().execute(baseURL);
			return true;
		case R.id.right:
			loadURL(adapter.getFeed().getNextLink().getHref());
			break;
		case R.id.left:
			if (navStack.size() > 0) {
				getActivity().onBackPressed();
			} else if (adapter.getFeed().getPreviousLink() != null) {
				loadURL(adapter.getFeed().getPreviousLink().getHref());
			}
			break;
		case R.id.search:
			onSearchClick();
			break;
		case R.id.prefs:
			Intent prefsIntent = new Intent(getActivity(), PageTurnerPrefsActivity.class);
			startActivity(prefsIntent);
			break;
		case R.id.open_library:
			Intent libIntent = new Intent(getActivity(), LibraryActivity.class);
			startActivity(libIntent);
			getActivity().finish();
			break;
		}		
		
		return true;
	}

	@Override
	public void onStop() {
		downloadDialog.dismiss();
		waitDialog.dismiss();

		super.onStop();
	}

	// TODO Refactor this. Let the platform push/pop fragments from the fragment stack.
	public void onBackPressed() {
		if (navStack.isEmpty()) {
			getActivity().finish();
			return;
		} 	
		
		navStack.pop();
		
		if (navStack.isEmpty()) {
			this.baseURL = config.getBaseOPDSFeed();
			new LoadOPDSTask().execute(baseURL);
		} else if ( navStack.peek().equals(CUSTOM_SITES_ID) ) {
			loadCustomSiteFeed();
		}else {
			new LoadOPDSTask().execute(navStack.peek());
		}
	}

	private class DownloadingCatalogAdapter extends CatalogListAdapter {

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View rowView;
			final Entry entry = getItem(position);

			LayoutInflater inflater = (LayoutInflater) getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
			final Link imgLink = getImageLink(getFeed(), entry);

			rowView = inflater.inflate(R.layout.catalog_item, parent, false);			 			

			loadBookDetails( rowView, entry, imgLink, true );
			return rowView;
		}
	}
	
	

	// this is our download file asynctask
	private class DownloadFileTask extends AsyncTask<String, Integer, String> {

		File destFile;
		
		private boolean openAfterDownload;
		private Exception failure;
		
		public DownloadFileTask(boolean openAfterDownload) {
			this.openAfterDownload = openAfterDownload;
		}

		@Override
		protected void onPreExecute() {
			super.onPreExecute();			
			downloadDialog.setMessage(getString(R.string.downloading));
			downloadDialog.show();
		}

		@Override
		protected String doInBackground(String... params) {

			try {

				String url = params[0];
				LOG.debug("Downloading: " + url);

				String fileName = url.substring(url.lastIndexOf('/') + 1);

				HttpParams httpParams = new BasicHttpParams();
				DefaultHttpClient client = new DefaultHttpClient(httpParams);
				client.getCredentialsProvider().setCredentials(
						new AuthScope(null, -1),
						new UsernamePasswordCredentials(user, password));
				HttpGet get = new HttpGet(url);

				HttpResponse response = client.execute(get);

				if (response.getStatusLine().getStatusCode() == 200) {

					File destFolder = new File(config.getDownloadsFolder());
					if (!destFolder.exists()) {
						destFolder.mkdirs();
					}
					
					/**
					 * Make sure we always store downloaded files as .epub, 
					 * so they show up in scans later on.
					 */
					if ( ! fileName.endsWith(".epub") ) {
						fileName = fileName + ".epub";
					}

					destFile = new File(destFolder, URLDecoder.decode(fileName));

					if (destFile.exists()) {
						destFile.delete();
					}

					// lenghtOfFile is used for calculating download progress
					long lenghtOfFile = response.getEntity().getContentLength();

					// this is where the file will be seen after the download
					FileOutputStream f = new FileOutputStream(destFile);
					
					try {
						// file input is from the url
						InputStream in = response.getEntity().getContent();

						// here's the download code
						byte[] buffer = new byte[1024];
						int len1 = 0;
						long total = 0;

						while ((len1 = in.read(buffer)) > 0) {

							// Make sure the user can cancel the download.
							if (isCancelled()) {
								return null;
							}

							total += len1;
							publishProgress((int) ((total * 100) / lenghtOfFile));
							f.write(buffer, 0, len1);
						}
					} finally {
						f.close();
					}
					
					Book book = new EpubReader().readEpub( new FileInputStream(destFile) );					
					libraryService.storeBook(destFile.getAbsolutePath(), book, false, config.isCopyToLibrayEnabled() );
					
				} else {
					this.failure = new RuntimeException(response
							.getStatusLine().getReasonPhrase());
					LOG.error("Download failed: "
							+ response.getStatusLine().getReasonPhrase());
				}

			} catch (Exception e) {
				LOG.error("Download failed.", e);
				this.failure = e;
			}

			return null;
		}

		@Override
		protected void onProgressUpdate(Integer... values) {
			downloadDialog.setProgress(values[0]);
		}

		@Override
		protected void onPostExecute(String unused) {
			
			if ( !isAdded() || getActivity() == null ) {
				return;
			}
			
			downloadDialog.hide();

			if (!isCancelled() && failure == null) {
				
				if ( openAfterDownload ) {				
					Intent intent;
					
					intent = new Intent(getActivity().getBaseContext(),
						ReadingActivity.class);
					intent.setData(Uri.parse(destFile.getAbsolutePath()));
				
					startActivity(intent);
					getActivity().finish();			
				} else {
					Toast.makeText(getActivity(), R.string.download_complete,
							Toast.LENGTH_LONG).show();
				}
				
			} else if (failure != null) {
				Toast.makeText(getActivity(), R.string.book_failed,
						Toast.LENGTH_LONG).show();
			}
		}
	}

	private void loadBookDetails(View layout, Entry entry, Link imageLink, boolean abbreviateText ) {
		
		HtmlSpanner spanner = new HtmlSpanner();
		
		TextView title = (TextView) layout.findViewById(R.id.itemTitle);
		TextView desc = (TextView) layout
				.findViewById(R.id.itemDescription);

		ImageView icon = (ImageView) layout.findViewById(R.id.itemIcon);
		loadImageLink(icon, imageLink, abbreviateText);

		title.setText(entry.getTitle());

		CharSequence text;
		
		if (entry.getContent() != null) {
			text = spanner.fromHtml(entry.getContent().getText());
		} else if (entry.getSummary() != null) {
			text = spanner.fromHtml(entry.getSummary());
		} else {
			text = "";
		}
		
		if (abbreviateText && text.length() > ABBREV_TEXT_LEN ) {
			text = text.subSequence(0, ABBREV_TEXT_LEN) + "…";
		}
		
		desc.setText(text);
	}
	
	private void loadImageLink(ImageView icon, Link imageLink, boolean scaleToThumbnail ) {

		try {

			if (imageLink != null && imageLink.getBinData() != null) {
				byte[] data = imageLink.getBinData();

				Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0,
						data.length);

				if ( scaleToThumbnail && bitmap.getWidth() > MAX_THUMBNAIL_WIDTH ) {
					int newHeight = getThumbnailWidth(bitmap.getHeight(), bitmap.getWidth() );
					icon.setImageBitmap( Bitmap.createScaledBitmap(bitmap,
							MAX_THUMBNAIL_WIDTH, newHeight, true));
					bitmap.recycle();				
				} else {
					icon.setImageBitmap(bitmap);
				}
				
				return;
			} 
		} catch (OutOfMemoryError mem ) {

		}
		
		icon.setImageDrawable(getResources().getDrawable(
					R.drawable.unknown_cover));
		
	}
	
	private int getThumbnailWidth( int originalHeight, int originalWidth ) {
		float factor = (float) originalHeight / (float) originalWidth;
		
		return (int) (MAX_THUMBNAIL_WIDTH * factor);
	}
	
	private void loadImageLink(Map<String, byte[]> cache, Link imageLink,
			String baseUrl) throws IOException {

		HttpParams httpParams = new BasicHttpParams();
		DefaultHttpClient client = new DefaultHttpClient(httpParams);
		client.getCredentialsProvider().setCredentials(new AuthScope(null, -1),
				new UsernamePasswordCredentials(user, password));

		if (imageLink != null) {
			String href = imageLink.getHref();

			if (cache.containsKey(href)) {
				imageLink.setBinData(cache.get(href));
			} else {

				String target = new URL(new URL(baseUrl), href).toString();

				LOG.info("Downloading image: " + target);

				HttpResponse resp = client.execute(new HttpGet(target));

				imageLink.setBinData(EntityUtils.toByteArray(resp.getEntity()));

				cache.put(href, imageLink.getBinData());
			}
		}
	}
	
	private void notifyLinkUpdated() {
		adapter.notifyDataSetChanged();
		
		if ( linkListener != null ) {
			linkListener.linkUpdated();
			linkListener = null;
		}		
	}
	
	private void showItemPopup(final Feed feed) {
		
		//If we're here, the feed always has just 1 entry
		final Entry entry = feed.getEntries().get(0);
		
		//Also, we don't want this entry on the nav-stack
		navStack.pop();
		
		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setTitle(feed.getTitle());
		View layout = getLayoutInflater(null).inflate(R.layout.catalog_download, null);
		builder.setView( layout );		

		TextView authorTextView = (TextView) layout
				.findViewById(R.id.itemAuthor);

		builder.setNegativeButton(android.R.string.cancel, null);
		
		if ( entry.getEpubLink() != null ) {
			
			String base = baseURL;

			if (!navStack.isEmpty()) {
				base = navStack.peek();
			}
			
			try {
				final URL url = new URL(new URL(base), entry.getEpubLink().getHref());
				
				builder.setPositiveButton(R.string.read, new DialogInterface.OnClickListener() {
					
					@Override
					public void onClick(DialogInterface dialog, int which) {
						new DownloadFileTask(true).execute(url.toExternalForm());						
					}
				});
				
				builder.setNeutralButton(R.string.add_to_library, new DialogInterface.OnClickListener() {
					
					@Override
					public void onClick(DialogInterface dialog, int which) {
						new DownloadFileTask(false).execute(url.toExternalForm());						
					}
				});				
				
			} catch (MalformedURLException e) {
				throw new RuntimeException(e);
			}
			
			
		}
		

		if (entry.getBuyLink() != null) {
			builder.setNeutralButton(R.string.buy_now, new DialogInterface.OnClickListener() {

				@Override
				public void onClick(DialogInterface dialog, int which) {
					String url = entry.getBuyLink().getHref();
					Intent i = new Intent(Intent.ACTION_VIEW);
					i.setData(Uri.parse(url));
					startActivity(i);
				}
			});
		}

		if (entry.getAuthor() != null) {
			String authorText = String.format(
					getString(R.string.book_by), entry.getAuthor()
							.getName());
			authorTextView.setText(authorText);
		} else {
			authorTextView.setText("");
		}
		
		final Link imgLink = getImageLink(feed, entry);
		
		loadBookDetails(layout, entry, imgLink, false);
		final ImageView icon = (ImageView) layout.findViewById(R.id.itemIcon);
		
		linkListener = new LinkListener() {
			
			@Override
			public void linkUpdated() {
				loadImageLink(icon, imgLink, false);				
			}
		};
		
		builder.show();
	}

	private void setNewFeed(Feed result) {		

		if (result != null) {
			
			if ( isLeafEntry(result) ) {
				showItemPopup(result);
			} else {
				adapter.setFeed(result);

				getSherlockActivity().supportInvalidateOptionsMenu();
				getSherlockActivity().getSupportActionBar().setTitle(result.getTitle());
			}
			
			waitDialog.hide();
		} else {
			waitDialog.hide();
			Toast.makeText(getActivity(), R.string.feed_failed,
					Toast.LENGTH_LONG).show();
		}
	}

	private class LoadFakeFeedTask extends AsyncTask<String, Integer, Feed> {

		private Entry singleEntry;

		public LoadFakeFeedTask(Entry entry) {
			this.singleEntry = entry;
		}

		@Override
		protected void onPreExecute() {
			waitDialog.setTitle(getString(R.string.loading_wait));
			waitDialog.show();
		}

		@Override
		protected Feed doInBackground(String... params) {
			Feed fakeFeed = new Feed();
			fakeFeed.addEntry(singleEntry);
			fakeFeed.setTitle(singleEntry.getTitle());

			try {
				loadImageLink(new HashMap<String, byte[]>(),
						singleEntry.getImageLink(), params[0]);
			} catch (IOException io) {
				LOG.error("Could not load image: ", io);
			}

			return fakeFeed;
		}

		@Override
		protected void onPostExecute(Feed result) {
			setNewFeed(result);
		}
	}
	
	/**
	 * Selects the right image link for an entry, based on preference.
	 * 
	 * @param feed
	 * @param entry
	 * @return
	 */
	private Link getImageLink(Feed feed, Entry entry) {
		Link[] linkOptions;

		if (isLeafEntry(feed)) {
			linkOptions = new Link[] { entry.getImageLink(), entry.getThumbnailLink() };
		} else {
			linkOptions = new Link[] { entry.getThumbnailLink(), entry.getImageLink() };						
		}
		
		Link imageLink = null;					
		for ( int i=0; imageLink == null && i < linkOptions.length; i++ ) {
			imageLink = linkOptions[i];
		}
		
		return imageLink;
	}

	private class LoadOPDSTask extends AsyncTask<String, Object, Feed>
			implements OnCancelListener {

		private Entry previousEntry;
		private boolean isBaseFeed;

		public LoadOPDSTask() {
			// leave previousEntry null
		}

		public LoadOPDSTask(Entry entry) {
			this.previousEntry = entry;
		}

		@Override
		protected void onPreExecute() {
			waitDialog.setTitle(getString(R.string.loading_wait));
			waitDialog.setOnCancelListener(this);
			waitDialog.show();
		}

		@Override
		public void onCancel(DialogInterface dialog) {
			this.cancel(true);
		}

		@TargetApi(Build.VERSION_CODES.FROYO)
		@Override
		protected Feed doInBackground(String... params) {

			String baseUrl = params[0];
			
			this.isBaseFeed = baseUrl.equals(config.getBaseOPDSFeed());

			if (baseUrl == null || baseUrl.trim().length() == 0) {
				return null;
			}

			baseUrl = baseUrl.trim();
			
			try {
				HttpParams httpParams = new BasicHttpParams();
				DefaultHttpClient client = new DefaultHttpClient(httpParams);
				client.getCredentialsProvider().setCredentials(
						new AuthScope(null, -1),
						new UsernamePasswordCredentials(user, password));
				HttpGet get = new HttpGet(baseUrl);
				HttpResponse response = client.execute(get);
				Feed feed = Nucular.readAtomFeedFromStream(response.getEntity()
						.getContent());
				List<Link> remoteImages = new ArrayList<Link>();

				for (final Entry entry : feed.getEntries()) {

					if (isCancelled()) {
						return feed;
					}

					Link imageLink = getImageLink(feed, entry);

					if (imageLink != null) {
						String href = imageLink.getHref();

						// If the image is contained in the feed, load it
						// directly
						if (href.startsWith("data:image/png;base64")) {
							String dataString = href.substring(href
									.indexOf(',') + 1);
							if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO) {							
								imageLink.setBinData(Base64.decode(dataString,
										Base64.DEFAULT));
							} else {
								// Slight hack for Android 2.1
								imageLink.setBinData(null);
							}
						} else {
							remoteImages.add(imageLink);
						}
					}
				}

				Link searchLink = feed.findByRel(AtomConstants.REL_SEARCH);

				if (searchLink != null) {
					URL mBaseUrl = new URL(baseUrl);
					URL mSearchUrl = new URL(mBaseUrl, searchLink.getHref());
					searchLink.setHref(mSearchUrl.toString());

					if (AtomConstants.TYPE_OPENSEARCH.equals(searchLink
							.getType())) {
						HttpGet searchGet = new HttpGet(searchLink.getHref());

						HttpResponse searchResponse = client.execute(searchGet);
						SearchDescription desc = Nucular
								.readOpenSearchFromStream(searchResponse
										.getEntity().getContent());

						if (desc.getSearchLink() != null) {
							searchLink.setType(AtomConstants.TYPE_ATOM);
							searchLink.setHref(desc.getSearchLink().getHref());
						}

					}
				}

				publishProgress(feed);

				Map<String, byte[]> cache = new HashMap<String, byte[]>();
				for (Link link : remoteImages) {
					loadImageLink(cache, link, baseUrl);
					publishProgress(link);
				}

				return feed;
			} catch (Exception e) {
				LOG.error("Download failed for url: " + baseUrl, e);
				return null;
			}

		}

		@Override
		protected void onPostExecute(Feed result) {
			
			if ( !isAdded() || getActivity() == null ) {
				return;
			}
			
			if (result == null) {
				setNewFeed(null);
			}
		}
		
		private void addCustomSitesEntry(Feed feed) {

			Entry entry = new Entry();
			entry.setTitle(getString(R.string.custom_site));
			entry.setSummary(getString(R.string.custom_site_desc));
			entry.setId(CUSTOM_SITES_ID);

			feed.addEntry(entry);
		}

		@Override
		protected void onProgressUpdate(Object... values) {
			if (values == null || values.length == 0) {
				return;
			}
			
			if ( !isAdded() || getActivity() == null ) {
				return;
			}

			Object val = values[0];

			if (val instanceof Feed) {
				Feed result = (Feed) val;

				/**
				 * This is a bit hacky: some feeds have the download link in the
				 * list, and clicking an item will take you to another list.
				 * 
				 * Since we always want to send the user to a single-item page
				 * before downloading, we have to fake it some times.
				 */

				if (previousEntry != null
						&& previousEntry.getEpubLink() != null) {
					if (result == null || result.getSize() != 1
							|| result.getEntries().get(0).getEpubLink() == null) {
						loadFakeFeed(previousEntry);
						return;
					}
				}
				
				if ( isBaseFeed ) {
					addCustomSitesEntry(result);
				}

				setNewFeed(result);
			} else if (val instanceof Link) {
				notifyLinkUpdated();
			}
		}

	}
}
