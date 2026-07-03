package ru.netology.nmedia.activity

import ru.netology.nmedia.adapter.PostsLoadStateViewHolder
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import androidx.paging.LoadStateAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.netology.nmedia.R
import ru.netology.nmedia.adapter.OnInteractionListener
import ru.netology.nmedia.adapter.PostsAdapter // ВАЖНО: Убедитесь, что ваш PostsAdapter наследует от PagingDataAdapter
import ru.netology.nmedia.auth.AppAuth
import ru.netology.nmedia.databinding.FragmentFeedBinding
import ru.netology.nmedia.dto.Post
import ru.netology.nmedia.repository.PostRepository
import ru.netology.nmedia.viewmodel.PostViewModel
import javax.inject.Inject


@AndroidEntryPoint
class FeedFragment : Fragment() {
    @Inject
    lateinit var repository: PostRepository

    @Inject
    lateinit var auth: AppAuth

    private val viewModel: PostViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentFeedBinding.inflate(inflater, container, false)

        val postsAdapter = PostsAdapter(object : OnInteractionListener {
            override fun onEdit(post: Post) { viewModel.edit(post) }
            override fun onLike(post: Post) { viewModel.likeById(post.id) }
            override fun onRemove(post: Post) { viewModel.removeById(post.id) }
            override fun onShare(post: Post) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, post.content)
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, null))
            }
        })

        // Создаём ДВА независимых экземпляра адаптера состояний загрузки
        val loadStateHeaderAdapter = object : LoadStateAdapter<PostsLoadStateViewHolder>() {
            override fun onBindViewHolder(holder: PostsLoadStateViewHolder, loadState: LoadState) {
                holder.bind(loadState)
            }

            override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): PostsLoadStateViewHolder {
                return PostsLoadStateViewHolder(parent)
            }
        }

        val loadStateFooterAdapter = object : LoadStateAdapter<PostsLoadStateViewHolder>() {
            override fun onBindViewHolder(holder: PostsLoadStateViewHolder, loadState: LoadState) {
                holder.bind(loadState)
            }

            override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): PostsLoadStateViewHolder {
                return PostsLoadStateViewHolder(parent)
            }
        }

        // Теперь передаём РАЗНЫЕ экземпляры для header и footer
        val adapterWithLoadStates = postsAdapter.withLoadStateHeaderAndFooter(
            header = loadStateHeaderAdapter,
            footer = loadStateFooterAdapter
        )

        binding.list.adapter = adapterWithLoadStates

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.data.collectLatest(postsAdapter::submitData)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                postsAdapter.loadStateFlow.collectLatest { loadStates ->
                    binding.swiperefresh.isRefreshing = loadStates.refresh is LoadState.Loading
                }
            }
        }

        binding.swiperefresh.setOnRefreshListener {
            postsAdapter.refresh()
        }

        binding.fab.setOnClickListener {
            findNavController().navigate(R.id.action_feedFragment_to_newPostFragment)
        }

        return binding.root
    }
}