package org.fossasia.openevent.general.auth

import android.Manifest
import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import org.fossasia.openevent.general.utils.ImageUtils.decodeBitmap
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.navigation.Navigation.findNavController
import androidx.navigation.fragment.navArgs
import com.squareup.picasso.Picasso
import kotlinx.android.synthetic.main.fragment_edit_profile.view.updateButton
import kotlinx.android.synthetic.main.fragment_edit_profile.view.toolbar
import kotlinx.android.synthetic.main.fragment_edit_profile.view.firstName
import kotlinx.android.synthetic.main.fragment_edit_profile.view.details
import com.squareup.picasso.MemoryPolicy
import kotlinx.android.synthetic.main.dialog_edit_profile_image.view.editImage
import kotlinx.android.synthetic.main.dialog_edit_profile_image.view.takeImage
import kotlinx.android.synthetic.main.dialog_edit_profile_image.view.replaceImage
import kotlinx.android.synthetic.main.dialog_edit_profile_image.view.removeImage
import kotlinx.android.synthetic.main.fragment_edit_profile.view.lastName
import kotlinx.android.synthetic.main.fragment_edit_profile.view.profilePhoto
import kotlinx.android.synthetic.main.fragment_edit_profile.view.progressBar
import kotlinx.android.synthetic.main.fragment_edit_profile.view.profilePhotoFab
import org.fossasia.openevent.general.CircleTransform
import org.fossasia.openevent.general.MainActivity
import org.fossasia.openevent.general.R
import org.fossasia.openevent.general.RotateBitmap
import org.fossasia.openevent.general.ComplexBackPressFragment
import org.fossasia.openevent.general.utils.Utils.hideSoftKeyboard
import org.fossasia.openevent.general.utils.Utils.requireDrawable
import org.fossasia.openevent.general.utils.extensions.nonNull
import org.fossasia.openevent.general.utils.nullToEmpty
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.FileNotFoundException
import org.fossasia.openevent.general.utils.Utils.setToolbar
import org.jetbrains.anko.design.snackbar

class EditProfileFragment : Fragment(), ComplexBackPressFragment {

    private val profileViewModel by viewModel<ProfileViewModel>()
    private val editProfileViewModel by viewModel<EditProfileViewModel>()
    private val safeArgs: EditProfileFragmentArgs by navArgs()
    private lateinit var rootView: View
    private var storagePermissionGranted = false
    private val PICK_IMAGE_REQUEST = 100
    private val READ_STORAGE = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    private val READ_STORAGE_REQUEST_CODE = 1

    private var cameraPermissionGranted = false
    private val TAKE_IMAGE_REQUEST = 101
    private val CAMERA_REQUEST = arrayOf(Manifest.permission.CAMERA)
    private val CAMERA_REQUEST_CODE = 2

    private lateinit var userFirstName: String
    private lateinit var userLastName: String
    private lateinit var userDetails: String
    private lateinit var userAvatar: String

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        rootView = inflater.inflate(R.layout.fragment_edit_profile, container, false)

        setToolbar(activity, show = false)
        rootView.toolbar.setNavigationOnClickListener {
            handleBackPress()
        }

        profileViewModel.user
            .nonNull()
            .observe(viewLifecycleOwner, Observer {
                loadUserUI(it)
            })

        val currentUser = editProfileViewModel.user.value
        if (currentUser == null) profileViewModel.getProfile() else loadUserUI(currentUser)

        editProfileViewModel.progress
            .nonNull()
            .observe(viewLifecycleOwner, Observer {
                rootView.progressBar.isVisible = it
            })

        editProfileViewModel.getUpdatedTempFile()
            .nonNull()
            .observe(viewLifecycleOwner, Observer { file ->
                // prevent picasso from storing tempAvatar cache,
                // if user select another image picasso will display tempAvatar instead of its own cache
                Picasso.get()
                    .load(file)
                    .placeholder(requireDrawable(requireContext(), R.drawable.ic_person_black))
                    .memoryPolicy(MemoryPolicy.NO_CACHE, MemoryPolicy.NO_STORE)
                    .transform(CircleTransform())
                    .into(rootView.profilePhoto)
            })

        storagePermissionGranted = (ContextCompat.checkSelfPermission(requireContext(),
            Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
        cameraPermissionGranted = (ContextCompat.checkSelfPermission(requireContext(),
            Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)

        rootView.updateButton.setOnClickListener {
            hideSoftKeyboard(context, rootView)
            editProfileViewModel.updateProfile(rootView.firstName.text.toString(),
                rootView.lastName.text.toString(), rootView.details.text.toString())
        }

        editProfileViewModel.message
            .nonNull()
            .observe(viewLifecycleOwner, Observer {
                rootView.snackbar(it)
                if (it == getString(R.string.user_update_success_message)) {
                    val thisActivity = activity
                    if (thisActivity is MainActivity) thisActivity.onSuperBackPressed()
                }
            })

        rootView.profilePhotoFab.setOnClickListener {
            showEditPhotoDialog()
        }

        return rootView
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intentData: Intent?) {
        super.onActivityResult(requestCode, resultCode, intentData)
        if (resultCode != Activity.RESULT_OK) return

        if (requestCode == PICK_IMAGE_REQUEST && intentData?.data != null) {
            val imageUri = intentData.data ?: return

            try {
                val selectedImage = RotateBitmap().handleSamplingAndRotationBitmap(requireContext(), imageUri)
                editProfileViewModel.encodedImage = selectedImage?.let { encodeImage(it) }
                editProfileViewModel.avatarUpdated = true
            } catch (e: FileNotFoundException) {
                Timber.d(e, "File Not Found Exception")
            }
        } else if (requestCode == TAKE_IMAGE_REQUEST) {
            val imageBitmap = intentData?.extras?.get("data")
            if (imageBitmap is Bitmap) {
                editProfileViewModel.encodedImage = imageBitmap.let { encodeImage(it) }
                editProfileViewModel.avatarUpdated = true
            }
        }
    }

    private fun loadUserUI(user: User) {
        userFirstName = user.firstName.nullToEmpty()
        userLastName = user.lastName.nullToEmpty()
        userDetails = user.details.nullToEmpty()
        userAvatar = user.avatarUrl.nullToEmpty()
        if (safeArgs.croppedImage.isEmpty()) {
            if (userAvatar.isNotEmpty() && !editProfileViewModel.avatarUpdated) {
                val drawable = requireDrawable(requireContext(), R.drawable.ic_account_circle_grey)
                Picasso.get()
                    .load(userAvatar)
                    .placeholder(drawable)
                    .transform(CircleTransform())
                    .into(rootView.profilePhoto)
            }
        } else {
            val croppedImage = decodeBitmap(safeArgs.croppedImage)
            editProfileViewModel.encodedImage = encodeImage(croppedImage)
            editProfileViewModel.avatarUpdated = true
        }
        if (rootView.firstName.text.isNullOrBlank()) {
            rootView.firstName.setText(userFirstName)
        }
        if (rootView.lastName.text.isNullOrBlank()) {
            rootView.lastName.setText(userLastName)
        }
        if (rootView.details.text.isNullOrBlank()) {
            rootView.details.setText(userDetails)
        }
    }

    private fun showEditPhotoDialog() {
        val editImageView = layoutInflater.inflate(R.layout.dialog_edit_profile_image, null)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(editImageView)
            .create()

        editImageView.editImage.setOnClickListener {

            if (this::userAvatar.isInitialized) {
                findNavController(rootView).navigate(
                    EditProfileFragmentDirections.actionEditProfileToCropImage(userAvatar))
            } else {
                rootView.snackbar(getString(R.string.error_editting_image_message))
            }

            dialog.cancel()
        }

        editImageView.removeImage.setOnClickListener {
            dialog.cancel()
            clearAvatar()
        }

        editImageView.takeImage.setOnClickListener {
            dialog.cancel()
            if (cameraPermissionGranted) {
                takeImage()
            } else {
                requestPermissions(CAMERA_REQUEST, CAMERA_REQUEST_CODE)
            }
        }

        editImageView.replaceImage.setOnClickListener {
            dialog.cancel()
            if (storagePermissionGranted) {
                showFileChooser()
            } else {
                requestPermissions(READ_STORAGE, READ_STORAGE_REQUEST_CODE)
            }
        }
        dialog.show()
    }

    private fun clearAvatar() {
        val drawable = requireDrawable(requireContext(), R.drawable.ic_account_circle_grey)
        Picasso.get()
            .load(R.drawable.ic_account_circle_grey)
            .placeholder(drawable)
            .transform(CircleTransform())
            .into(rootView.profilePhoto)
        editProfileViewModel.encodedImage = encodeImage(drawable.toBitmap(120, 120))
        editProfileViewModel.avatarUpdated = true
    }

    private fun encodeImage(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        val bytes = baos.toByteArray()

        // create temp file
        try {

            val tempAvatar = File(context?.cacheDir, "tempAvatar")
            if (tempAvatar.exists()) {
                tempAvatar.delete()
            }
            val fos = FileOutputStream(tempAvatar)
            fos.write(bytes)
            fos.flush()
            fos.close()

            editProfileViewModel.setUpdatedTempFile(tempAvatar)
        } catch (e: IOException) {
            e.printStackTrace()
        }

        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.DEFAULT)
    }

    private fun takeImage() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        startActivityForResult(intent, TAKE_IMAGE_REQUEST)
    }

    private fun showFileChooser() {
        val intent = Intent()
        intent.type = "image/*"
        intent.action = Intent.ACTION_GET_CONTENT
        startActivityForResult(Intent.createChooser(intent, getString(R.string.select_image)), PICK_IMAGE_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == READ_STORAGE_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                storagePermissionGranted = true
                rootView.snackbar(getString(R.string.permission_granted_message, getString(R.string.external_storage)))
                showFileChooser()
            } else {
                rootView.snackbar(getString(R.string.permission_denied_message, getString(R.string.external_storage)))
            }
        } else if (requestCode == CAMERA_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                cameraPermissionGranted = true
                rootView.snackbar(getString(R.string.permission_granted_message, getString(R.string.camera)))
                takeImage()
            } else {
                rootView.snackbar(getString(R.string.permission_denied_message, getString(R.string.camera)))
            }
        }
    }

    /**
     * Handles back press when up button or back button is pressed
     */
    override fun handleBackPress() {
        val thisActivity = activity
        if (!editProfileViewModel.avatarUpdated && rootView.lastName.text.toString() == userLastName &&
            rootView.firstName.text.toString() == userFirstName && rootView.details.text.toString() == userDetails) {
            findNavController(rootView).popBackStack()
        } else {
            hideSoftKeyboard(context, rootView)
            val dialog = AlertDialog.Builder(requireContext())
            dialog.setMessage(getString(R.string.changes_not_saved))
            dialog.setNegativeButton(getString(R.string.discard)) { _, _ ->
                if (thisActivity is MainActivity) thisActivity.onSuperBackPressed()
            }
            dialog.setPositiveButton(getString(R.string.save)) { _, _ ->
                editProfileViewModel.updateProfile(rootView.firstName.text.toString(),
                    rootView.lastName.text.toString(), rootView.details.text.toString())
            }
            dialog.create().show()
        }
    }

    override fun onDestroyView() {
        val activity = activity as? AppCompatActivity
        activity?.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        setHasOptionsMenu(false)
        super.onDestroyView()
    }
}
