package com.marinov.boletosfei

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BoletosFragment : Fragment() {

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var recyclerBoletos: RecyclerView
    private lateinit var progressCircular: CircularProgressIndicator
    private lateinit var barOffline: LinearLayout
    private lateinit var txtSemDados: TextView
    private lateinit var btnTentar: Button
    private val adapter = BoletoAdapter(emptyList()) { boleto -> onBaixarBoleto(boleto) }

    private var adView: AdView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_boletos, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        recyclerBoletos = view.findViewById(R.id.recyclerBoletos)
        progressCircular = view.findViewById(R.id.progress_circular)
        barOffline = view.findViewById(R.id.barOffline)
        txtSemDados = view.findViewById(R.id.txt_sem_dados)
        btnTentar = view.findViewById(R.id.btnLogin)

        // Inicializa AdMob
        adView = view.findViewById(R.id.adView)
        val adRequest = AdRequest.Builder().build()
        adView?.loadAd(adRequest)

        recyclerBoletos.layoutManager = LinearLayoutManager(requireContext())
        recyclerBoletos.adapter = adapter

        btnTentar.setOnClickListener { loadBoletos() }

        swipeRefreshLayout.setOnRefreshListener { loadBoletos() }
        swipeRefreshLayout.setDistanceToTriggerSync(250)

        loadBoletos()
    }

    override fun onResume() {
        super.onResume()
        adView?.resume()
    }

    override fun onPause() {
        adView?.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        adView?.destroy()
        adView = null
        super.onDestroyView()
    }

    fun loadBoletos() {
        lifecycleScope.launch {
            val mainActivity = activity as? MainActivity ?: return@launch
            showLoading()

            val status = mainActivity.checkConnectionAndSession()

            if (status == MainActivity.STATUS_LOGIN_NEEDED) {
                return@launch
            }

            val online = status == MainActivity.STATUS_ONLINE_OK

            if (online) hideOfflineBar() else showOfflineBar()

            val boletos = try {
                if (online) {
                    withContext(Dispatchers.IO) { Dados.getBoletos(online = true) }
                } else {
                    withContext(Dispatchers.IO) { Dados.getBoletos(online = false) }
                }
            } catch (_: SessionExpiredException) {
                mainActivity.checkConnectionAndSession()
                return@launch
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { Dados.getBoletos(online = false) }
            }

            val boletosOrdenados = boletos.sortedByDescending { boleto ->
                val partes = boleto.vencimento.split("/")
                if (partes.size == 3) "${partes[2]}-${partes[1]}-${partes[0]}" else boleto.vencimento
            }

            if (swipeRefreshLayout.isRefreshing) {
                swipeRefreshLayout.isRefreshing = false
            }

            if (boletosOrdenados.isEmpty()) {
                showEmptyState()
            } else {
                adapter.update(boletosOrdenados)
                showContent()
            }
        }
    }

    private fun onBaixarBoleto(boleto: Dados.Boleto) {
        if (boleto.tituloId.isBlank()) {
            Toast.makeText(requireContext(), "Boleto sem ID disponível.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            Toast.makeText(requireContext(), "Gerando boleto, aguarde…", Toast.LENGTH_SHORT).show()

            val uri = withContext(Dispatchers.IO) {
                runCatching { Dados.baixaBoleto(boleto.tituloId, boleto.vencimento) }.getOrNull()
            }

            if (uri == null) {
                Toast.makeText(requireContext(), "Erro ao gerar boleto. Tente novamente.", Toast.LENGTH_LONG).show()
                return@launch
            }

            Toast.makeText(requireContext(), "Salvo em Downloads/BoletosFEI/", Toast.LENGTH_SHORT).show()

            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "*/*")
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(fallback)
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "Nenhum aplicativo para abrir PDF encontrado.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── Estados de UI ───────────────────────────────────

    private fun showLoading() {
        if (!isAdded) return
        progressCircular.visibility = View.VISIBLE
        recyclerBoletos.visibility = View.GONE
        txtSemDados.visibility = View.GONE
    }

    private fun showContent() {
        if (!isAdded) return
        progressCircular.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        recyclerBoletos.visibility = View.VISIBLE
    }

    private fun showEmptyState() {
        if (!isAdded) return
        progressCircular.visibility = View.GONE
        recyclerBoletos.visibility = View.GONE
        txtSemDados.visibility = View.VISIBLE
    }

    private fun showOfflineBar() {
        if (isAdded) barOffline.visibility = View.VISIBLE
    }

    private fun hideOfflineBar() {
        if (isAdded) barOffline.visibility = View.GONE
    }

    // ── Adapter ─────────────────────────────────────────

    inner class BoletoAdapter(
        private var boletos: List<Dados.Boleto>,
        private val onBaixar: (Dados.Boleto) -> Unit
    ) : RecyclerView.Adapter<BoletoAdapter.ViewHolder>() {

        fun update(newList: List<Dados.Boleto>) {
            boletos = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_boleto, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(boletos[position])
        }

        override fun getItemCount() = boletos.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvVencimento: TextView = itemView.findViewById(R.id.tvVencimento)
            private val chipStatus: TextView = itemView.findViewById(R.id.chipStatus)
            private val rowDataPagamento: View = itemView.findViewById(R.id.rowDataPagamento)
            private val tvDataPagamento: TextView = itemView.findViewById(R.id.tvDataPagamento)
            private val btnBaixarBoleto: com.google.android.material.button.MaterialButton =
                itemView.findViewById(R.id.btnBaixarBoleto)

            fun bind(boleto: Dados.Boleto) {
                tvVencimento.text = boleto.vencimento

                val isPago = boleto.status.equals("PAGO", ignoreCase = true)

                chipStatus.text = boleto.status
                if (isPago) {
                    chipStatus.backgroundTintList =
                        ContextCompat.getColorStateList(itemView.context, R.color.status_pago_bg)
                    chipStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_pago_text))
                } else {
                    chipStatus.backgroundTintList =
                        ContextCompat.getColorStateList(itemView.context, R.color.status_aberto_bg)
                    chipStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_aberto_text))
                }

                if (isPago && boleto.dataPagamento.isNotBlank()) {
                    rowDataPagamento.visibility = View.VISIBLE
                    tvDataPagamento.text = boleto.dataPagamento
                } else {
                    rowDataPagamento.visibility = View.GONE
                }

                if (!isPago && boleto.tituloId.isNotBlank()) {
                    btnBaixarBoleto.visibility = View.VISIBLE
                    btnBaixarBoleto.setOnClickListener { onBaixar(boleto) }
                } else {
                    btnBaixarBoleto.visibility = View.GONE
                }
            }
        }
    }
}