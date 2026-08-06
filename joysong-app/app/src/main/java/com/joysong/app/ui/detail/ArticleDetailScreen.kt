package com.joysong.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import android.webkit.WebView
import android.webkit.WebViewClient
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.joysong.app.R
import com.joysong.app.domain.model.ExpertArticle
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.FavoriteButton
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.PlaceholderImage
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.Primary
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary

@Composable
fun ArticleDetailScreen(
    articleId: String,
    onBackClick: () -> Unit,
    onDoctorClick: (String) -> Unit = {},
    viewModel: DetailViewModel = hiltViewModel()
) {
    val articleState by viewModel.article.collectAsState()

    LaunchedEffect(articleId) {
        viewModel.loadArticle(articleId)
        viewModel.checkArticleFavoriteStatus(articleId)
    }

    Scaffold(
        topBar = { JoysongTopBar(title = stringResource(R.string.article_detail), onBackClick = onBackClick) }
    ) { innerPadding ->
        when (articleState) {
            is DetailUiState.Loading -> LoadingIndicator()
            is DetailUiState.Error -> ErrorView(
                message = (articleState as DetailUiState.Error).message,
                onRetry = { viewModel.loadArticle(articleId) }
            )
            is DetailUiState.Success -> {
                val article = (articleState as DetailUiState.Success<ExpertArticle>).data
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .background(Background)
                ) {
                    if (article.coverImage.isNotBlank()) {
                        AsyncImage(
                            model = article.coverImage,
                            contentDescription = article.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        )
                    } else {
                        PlaceholderImage(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            text = stringResource(R.string.article_placeholder)
                        )
                    }
                    Column(modifier = Modifier.padding(16.dp)) {
                        // 标题行：标题 + 收藏按钮
                        Row(
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = article.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            FavoriteButton(
                                isFavorited = viewModel.isFavorited.value,
                                onClick = {
                                    viewModel.toggleArticleFavorite(
                                        articleId = articleId,
                                        targetName = article.title,
                                        targetImage = article.coverImage
                                    )
                                },
                                size = 28.dp
                            )
                        }
                        val hasDoctor = article.doctorId.isNotEmpty()
                        Text(
                            text = "${article.authorName} · ${article.publishDate}",
                            fontSize = 14.sp,
                            color = if (hasDoctor) Primary else TextHint,
                            fontWeight = if (hasDoctor) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .padding(top = 10.dp)
                                .then(
                                    if (hasDoctor) Modifier.clickable { onDoctorClick(article.doctorId) }
                                    else Modifier
                                )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = article.summary,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        // 使用 WebView 渲染富文本 HTML 内容
                        val textColor = TextSecondary.toArgb()
                        val bgColor = Background.toArgb()
                        var webViewHeight by remember { mutableIntStateOf(0) }
                        AndroidView(
                            factory = { context ->
                                WebView(context).apply {
                                    isVerticalScrollBarEnabled = false
                                    isHorizontalScrollBarEnabled = false
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            view?.let { wv ->
                                                webViewHeight = wv.contentHeight
                                            }
                                        }
                                    }
                                    settings.javaScriptEnabled = false
                                    settings.loadWithOverviewMode = true
                                    setBackgroundColor(bgColor)
                                    val htmlContent = """
                                        <html>
                                        <head>
                                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                        <style>
                                            body {
                                                font-family: sans-serif;
                                                font-size: 15px;
                                                line-height: 1.8;
                                                color: #${String.format("%06X", 0xFFFFFF and textColor)};
                                                margin: 0;
                                                padding: 0;
                                                word-wrap: break-word;
                                            }
                                            img {
                                                max-width: 100%;
                                                height: auto;
                                                border-radius: 8px;
                                                margin: 8px 0;
                                            }
                                            p { margin: 6px 0; }
                                            h1, h2, h3, h4 { margin: 12px 0 6px 0; }
                                            blockquote {
                                                border-left: 3px solid #E8577B;
                                                margin: 8px 0;
                                                padding: 4px 12px;
                                                color: #666;
                                                background: #f9f9f9;
                                            }
                                            a { color: #E8577B; }
                                        </style>
                                        </head>
                                        <body>${article.content}</body>
                                        </html>
                                    """.trimIndent()
                                    loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (webViewHeight > 0) Modifier.height((webViewHeight * 1.1f).dp)
                                    else Modifier.height(300.dp)
                                )
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
