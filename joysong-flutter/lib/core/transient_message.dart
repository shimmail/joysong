import 'package:flutter/material.dart';

const _transientMessageDuration = Duration(seconds: 2);

void showTransientMessage(BuildContext context, String message) {
  final messenger = ScaffoldMessenger.of(context);
  messenger
    ..removeCurrentSnackBar()
    ..showSnackBar(
      SnackBar(
        content: Text(message),
        duration: _transientMessageDuration,
      ),
    );
}
