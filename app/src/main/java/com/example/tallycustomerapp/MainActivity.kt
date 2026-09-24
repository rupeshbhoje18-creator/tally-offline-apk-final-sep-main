package com.example.tallycustomerapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.example.tallycustomerapp.data.AppDatabase
import com.example.tallycustomerapp.data.CompanyEntity
import com.example.tallycustomerapp.data.CompanySummary
import com.example.tallycustomerapp.databinding.ActivityMainBinding
import com.example.tallycustomerapp.databinding.FragmentOfflineBinding
import com.example.tallycustomerapp.databinding.ItemCompanyBinding
import com.example.tallycustomerapp.offline.OfflineViewModel
import com.example.tallycustomerapp.offline.OfflineViewModelFactory
import com.example.tallycustomerapp.web.SyncCollector
import com.example.tallycustomerapp.web.WebAppInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val db by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNavigation()
    }

    private fun setupNavigation() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, LivePortalFragment())
            .commit()
        binding.bottomNav.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.tab_live -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, LivePortalFragment())
                        .commit()
                    true
                }
                R.id.tab_offline -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, OfflineFragment())
                        .commit()
                    true
                }
                else -> false
            }
        }
    }

    class LivePortalFragment : Fragment() {
        private lateinit var db: AppDatabase

        @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
        ): View {
            val view = inflater.inflate(R.layout.fragment_live, container, false)
            val webView: WebView = view.findViewById(R.id.webView)
            db = AppDatabase.getDatabase(requireContext())

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            webView.settings.setSupportMultipleWindows(false)
            webView.settings.userAgentString = webView.settings.userAgentString + " TallyOfflineMirror/1.0"
            webView.webChromeClient = WebChromeClient()

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            restoreCookies(requireContext(), cookieManager)

            webView.addJavascriptInterface(
                WebAppInterface(requireContext(), db),
                "AndroidBridge"
            )

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    saveCookies(requireContext(), cookieManager)
                    webView.postDelayed({
                        webView.evaluateJavascript(SyncCollector.script(), null)
                    }, 250)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }
            }

            webView.loadUrl("https://customer.tallysolutions.com/customerapp/")
            return view
        }

        private fun saveCookies(context: Context, cookieManager: CookieManager) {
            val cookies = cookieManager.getCookie("https://customer.tallysolutions.com/customerapp/")
            context.getSharedPreferences("cookies", Context.MODE_PRIVATE).edit()
                .putString("tally_cookies", cookies)
                .apply()
        }

        private fun restoreCookies(context: Context, cookieManager: CookieManager) {
            val cookies = context.getSharedPreferences("cookies", Context.MODE_PRIVATE)
                .getString("tally_cookies", null)
            if (!cookies.isNullOrEmpty()) {
                cookieManager.setCookie("https://customer.tallysolutions.com/customerapp/", cookies)
                CookieManager.getInstance().flush()
            }
        }
    }

    class OfflineFragment : Fragment() {
        private var _binding: FragmentOfflineBinding? = null
        private val binding get() = _binding!!
        private lateinit var adapter: CompanyAdapter
        private lateinit var viewModel: OfflineViewModel

        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
        ): View {
            _binding = FragmentOfflineBinding.inflate(inflater, container, false)
            val db = AppDatabase.getDatabase(requireContext())
            viewModel = ViewModelProvider(this, OfflineViewModelFactory(db.companyDao()))
                .get(OfflineViewModel::class.java)
            setupRecyclerView()
            observeViewModel()
            return binding.root
        }

        override fun onResume() {
            super.onResume()
            if (::viewModel.isInitialized) viewModel.loadCompanies()
        }

        private fun setupRecyclerView() {
            adapter = CompanyAdapter { summary ->
                showPages(summary)
            }
            binding.recyclerOffline.layoutManager = LinearLayoutManager(requireContext())
            binding.recyclerOffline.adapter = adapter
        }

        private fun observeViewModel() {
            viewModel.companiesLiveData.observe(viewLifecycleOwner) { companies ->
                adapter.submitList(companies)
                binding.textEmpty.visibility = if (companies.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        private fun showPages(summary: CompanySummary) {
            viewLifecycleOwner.lifecycleScope.launch {
                val pages = AppDatabase.getDatabase(requireContext()).offlineDao()
                    .getPageSnapshots(summary.id)
                if (pages.isEmpty()) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(summary.companyName)
                        .setMessage(
                            "Serial No: ${summary.serialNumber}\n" +
                                "Offline pages: 0\n" +
                                "Last Sync: ${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", summary.lastSynced)}"
                        )
                        .setPositiveButton("Close", null)
                        .show()
                    return@launchWhenStarted
                }

                val labels = pages.map { page ->
                    "${page.title.ifBlank { "Tally Page" }}\n${page.route}"
                }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("${summary.companyName} • Offline Pages (${pages.size})")
                    .setItems(labels) { _, which ->
                        startActivity(
                            Intent(requireContext(), OfflinePageActivity::class.java)
                                .putExtra(OfflinePageActivity.EXTRA_PAGE_ID, pages[which].id)
                        )
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }

        override fun onDestroyView() {
            super.onDestroyView()
            _binding = null
        }
    }

    class CompanyAdapter(
        private val onItemClick: (CompanySummary) -> Unit
    ) : ListAdapter<CompanySummary, CompanyAdapter.VH>(
        object : DiffUtil.ItemCallback<CompanySummary>() {
            override fun areItemsTheSame(old: CompanySummary, new: CompanySummary) = old.id == new.id
            override fun areContentsTheSame(old: CompanySummary, new: CompanySummary) = old == new
        }
    ) {
        inner class VH(val binding: ItemCompanyBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ItemCompanyBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val company = getItem(position)
            holder.binding.textCompanyName.text = company.companyName
            holder.binding.textSerialNumber.text = "Serial No: ${company.serialNumber}"
            holder.binding.textLastSynced.text =
                "Pages: ${company.pageCount}  •  Ledgers: ${company.ledgerCount}  •  Vouchers: ${company.voucherCount}"
            holder.binding.root.setOnClickListener { onItemClick(company) }
        }
    }
}
