package com.marinov.boletosfei

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException

class SessionExpiredException(message: String) : Exception(message)

object Dados {

    private const val PREFS_NAME = "DadosFEI"
    private const val KEY_PERFIL = "perfil_cache"
    private const val KEY_LAST_UPDATE_PERFIL = "last_update_perfil"
    private const val KEY_BOLETOS = "boletos_cache"
    private const val KEY_LAST_UPDATE_BOLETOS = "last_update_boletos"
    private const val URL_PERFIL = "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/dados-pessoais"
    private const val URL_BOLETOS = "https://interage.fei.org.br/secureserver/portal/graduacao/tesouraria/consultas/boletos"
    private const val URL_GERAR_BOLETO = "https://interage.fei.org.br/secureserver/portal/graduacao/tesouraria/consultas/boletos/titulos/gerar"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"

    private lateinit var appContext: Context
    private val gson = Gson()
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    data class Perfil(
        val nome: String,
        val matricula: String,
        val curso: String,
        val email: String
    )

    data class Boleto(
        val vencimento: String,
        val status: String,
        val dataPagamento: String,
        val tituloId: String
    )

    suspend fun retornaDadosUsuario(online: Boolean): Perfil {
        return if (online) {
            try {
                val perfil = fetchPerfilFromServer()
                savePerfilCache(perfil)
                perfil
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e("Dados", "Erro ao buscar perfil online", e)
                getCachedPerfil() ?: Perfil("", "", "", "")
            }
        } else {
            getCachedPerfil() ?: Perfil("", "", "", "")
        }
    }

    suspend fun getBoletos(online: Boolean): List<Boleto> {
        return if (online) {
            try {
                val boletos = fetchBoletosFromServer()
                saveBoletosCache(boletos)
                boletos
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e("Dados", "Erro ao buscar boletos online", e)
                getCachedBoletos()
            }
        } else {
            getCachedBoletos()
        }
    }

    suspend fun atualizaBoletos(): Boolean {
        return try {
            val novos = fetchBoletosFromServer()
            val antigos = getCachedBoletos()

            // Evita notificação no primeiro preenchimento do cache
            if (antigos.isEmpty()) {
                saveBoletosCache(novos)
                return false
            }

            val alterado = novos.size != antigos.size ||
                    novos.zip(antigos).any { (novo, antigo) ->
                        novo.vencimento != antigo.vencimento ||
                                novo.status != antigo.status ||
                                novo.dataPagamento != antigo.dataPagamento
                    }
            if (alterado) saveBoletosCache(novos)
            alterado
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            Log.e("Dados", "Erro em atualizaBoletos", e)
            false
        }
    }

    suspend fun baixaBoleto(tituloId: String, vencimento: String): android.net.Uri? = withContext(Dispatchers.IO) {
        try {
            val partes = vencimento.split("/")
            val nomeArquivo = if (partes.size == 3) {
                "${partes[2]}_${partes[1]}.pdf"
            } else {
                "$tituloId.pdf"
            }

            // 1. GET da página de boletos via Jsoup.execute() para capturar cookies da resposta
            val webViewCookies = CookieManager.getInstance().getCookie(URL_BOLETOS) ?: ""

            val getResponse = Jsoup.connect(URL_BOLETOS)
                .userAgent(USER_AGENT)
                .header("Cookie", webViewCookies)
                .timeout(20_000)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.GET)
                .execute()

            // 2. Extrair CSRF token do HTML
            val pageDoc = getResponse.parse()
            val csrfToken = pageDoc
                .selectFirst("#form-gerar-boletos input[name=__RequestVerificationToken]")
                ?.`val`()
                ?: run {
                    Log.e("Dados", "CSRF token não encontrado na página de boletos")
                    return@withContext null
                }

            Log.d("Dados", "CSRF token obtido: ${csrfToken.take(20)}…")

            // 3. Mesclar cookies da WebView + cookies novos da resposta do GET
            //    (inclui o __RequestVerificationToken_<hash> que o servidor setou)
            val responseCookies = getResponse.cookies() // Map<String, String>
            val cookiesMerged = buildString {
                append(webViewCookies)
                for ((name, value) in responseCookies) {
                    if (isNotEmpty()) append("; ")
                    append("$name=$value")
                }
            }

            Log.d("Dados", "Cookies para POST: ${cookiesMerged.take(120)}…")

            // 4. POST com todos os cookies (WebView + resposta do GET)
            val postResponse = Jsoup.connect(URL_GERAR_BOLETO)
                .userAgent(USER_AGENT)
                .header("Cookie", cookiesMerged)
                .header("Referer", URL_BOLETOS)
                .header("Accept", "application/pdf,text/html,*/*")
                .data("__RequestVerificationToken", csrfToken)
                .data("respFinanceiro", "0")
                .data("titulos", tituloId)
                .method(Connection.Method.POST)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .timeout(30_000)
                .maxBodySize(10485760)
                .execute()

            val httpStatus = postResponse.statusCode()
            val contentType = postResponse.contentType() ?: ""
            Log.d("Dados", "BaixaBoleto: HTTP $httpStatus, Content-Type=$contentType")

            // 5. Verificar se é PDF, facilita debug futuro
            if (!contentType.contains("pdf", ignoreCase = true)) {
                Log.e("Dados", "Resposta não é PDF (Content-Type=$contentType). HTML recebido:\n${postResponse.body().take(500)}")
                return@withContext null
            }

            val pdfBytes = postResponse.bodyAsBytes()
            if (pdfBytes.size < 1000) {
                Log.e("Dados", "PDF suspeito: apenas ${pdfBytes.size} bytes")
                return@withContext null
            }

            // 6. Salvar em Downloads/BoletosFEI/
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val boletoDir = File(downloadsDir, "BoletosFEI").also { it.mkdirs() }
            val outputFile = File(boletoDir, nomeArquivo)
            outputFile.writeBytes(pdfBytes)

            Log.d("Dados", "Boleto salvo: ${outputFile.absolutePath} (${outputFile.length()} bytes)")

            android.media.MediaScannerConnection.scanFile(
                appContext,
                arrayOf(outputFile.absolutePath),
                arrayOf("application/pdf"),
                null
            )

            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                outputFile
            )
        } catch (e: Exception) {
            Log.e("Dados", "Erro ao baixar boleto $tituloId", e)
            null
        }
    }

    @Throws(IOException::class)
    private suspend fun fetchPage(url: String): Document = withContext(Dispatchers.IO) {
        val cookies = CookieManager.getInstance().getCookie(url)
        try {
            val conn = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(20000)
            if (!cookies.isNullOrBlank()) {
                conn.header("Cookie", cookies)
            }
            conn.get()
        } catch (e: IOException) {
            Log.e("Dados", "Erro de rede ao buscar $url", e)
            throw e
        }
    }

    private suspend fun fetchPerfilFromServer(): Perfil {
        val doc = fetchPage(URL_PERFIL)
        val panelBody = doc.selectFirst("body > div.container > div:nth-child(2) > div.col-md-9 > div.panel.panel-default.hidden-xs.bloco-conteudo-cabecalho > div.panel-body")
            ?: throw SessionExpiredException("Painel de perfil não encontrado")
        var nome = ""
        var matricula = ""
        var curso = ""
        panelBody.children().forEach { col ->
            val b = col.selectFirst("b")?.text()?.trim() ?: ""
            val em = col.selectFirst("small em")?.text()?.trim() ?: ""
            when {
                b.equals("Nome", ignoreCase = true) -> nome = em
                b.equals("Matrícula", ignoreCase = true) -> matricula = em
                b.equals("Curso", ignoreCase = true) -> curso = em
            }
        }
        val emailGroup = doc.selectFirst("#form-atualizar-dados-pessoais > div:nth-child(19)")
        val emailElement = emailGroup?.selectFirst("p.form-control-static")
        val email = emailElement?.text()?.trim() ?: ""
        Log.d("Dados", "Perfil carregado: $nome")
        return Perfil(nome, matricula, curso, email)
    }

    private suspend fun fetchBoletosFromServer(): List<Boleto> {
        val doc = fetchPage(URL_BOLETOS)
        val form = doc.selectFirst("#form-gerar-boletos")
            ?: throw SessionExpiredException("Formulário de boletos não encontrado — sessão inválida")

        val tabela = form.selectFirst("table.table")
            ?: throw SessionExpiredException("Tabela de boletos não encontrada")

        val boletos = mutableListOf<Boleto>()
        val linhas = tabela.select("tbody > tr")

        for (linha in linhas) {
            val vencimento = linha.selectFirst("td[class*=Vencimento]")?.text()?.trim() ?: continue
            val status = linha.selectFirst("td[class*=Status]")?.text()?.trim() ?: continue
            val dataPagamento = linha.selectFirst("td[class*=Data]")?.text()?.trim() ?: ""
            val tituloId = linha.selectFirst("input[name=titulos]")?.`val`()?.trim() ?: ""
            if (vencimento.isNotEmpty() && status.isNotEmpty()) {
                boletos.add(Boleto(vencimento, status, dataPagamento, tituloId))
            }
        }

        Log.d("Dados", "Boletos carregados: ${boletos.size}")
        return boletos
    }

    private fun savePerfilCache(perfil: Perfil) {
        prefs.edit {
            putString(KEY_PERFIL, gson.toJson(perfil))
                .putLong(KEY_LAST_UPDATE_PERFIL, System.currentTimeMillis())
        }
    }

    private fun getCachedPerfil(): Perfil? {
        val json = prefs.getString(KEY_PERFIL, null) ?: return null
        return try {
            gson.fromJson(json, Perfil::class.java)
        } catch (_: Exception) { null }
    }

    private fun saveBoletosCache(boletos: List<Boleto>) {
        prefs.edit {
            putString(KEY_BOLETOS, gson.toJson(boletos))
                .putLong(KEY_LAST_UPDATE_BOLETOS, System.currentTimeMillis())
        }
    }

    private fun getCachedBoletos(): List<Boleto> {
        val json = prefs.getString(KEY_BOLETOS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Boleto>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
}